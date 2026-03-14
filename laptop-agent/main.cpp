#include <iostream>
#include <string>
#include <thread>
#include <vector>
#include <cstring>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <cstdio>
#include <memory>
#include <array>
#include <fcntl.h>
#include <util.h> 
#include <termios.h>
#include <sys/ioctl.h>
#include <sys/sysctl.h>
#include <mach/mach_host.h>
#include <mach/mach_init.h>
#include <mutex>
#include <sstream>
#include "json.hpp"

using json = nlohmann::json;
#define PORT 19090

std::string exec_shell(const std::string& cmd) {
    std::array<char, 512> buffer;
    std::string result;
    std::unique_ptr<FILE, decltype(&pclose)> pipe(popen(cmd.c_str(), "r"), pclose);
    if (!pipe) return "";
    while (fgets(buffer.data(), buffer.size(), pipe.get()) != nullptr) {
        result += buffer.data();
    }
    return result;
}

class SystemMetrics {
public:
    static std::string get_cpu() {
        host_cpu_load_info_data_t cpu_load;
        mach_msg_type_number_t count = HOST_CPU_LOAD_INFO_COUNT;
        if (host_statistics(mach_host_self(), HOST_CPU_LOAD_INFO, (host_info_t)&cpu_load, &count) != KERN_SUCCESS) return "0%";
        static unsigned long long prev_user = 0, prev_system = 0, prev_idle = 0, prev_nice = 0;
        unsigned long long user = cpu_load.cpu_ticks[CPU_STATE_USER];
        unsigned long long system = cpu_load.cpu_ticks[CPU_STATE_SYSTEM];
        unsigned long long idle = cpu_load.cpu_ticks[CPU_STATE_IDLE];
        unsigned long long nice = cpu_load.cpu_ticks[CPU_STATE_NICE];
        unsigned long long total = (user - prev_user) + (system - prev_system) + (idle - prev_idle) + (nice - prev_nice);
        double usage = (total == 0) ? 0 : 100.0 * (double)((user - prev_user) + (system - prev_system)) / (double)total;
        prev_user = user; prev_system = system; prev_idle = idle; prev_nice = nice;
        char buf[16]; snprintf(buf, 16, "%.1f%%", usage);
        return std::string(buf);
    }
    static std::string get_mem() {
        int64_t memsize;
        size_t len = sizeof(memsize);
        sysctlbyname("hw.memsize", &memsize, &len, NULL, 0);
        return std::to_string(memsize / (1024 * 1024 * 1024)) + "GB";
    }
};

class Protocol {
public:
    static std::string terminal(const std::string& data) { return json({{"type", "terminal"}, {"data", data}}).dump() + "\n"; }
    static std::string log(const std::string& level, const std::string& message) { return json({{"type", "log"}, {"level", level}, {"message", message}}).dump() + "\n"; }
    static std::string status(const std::string& cpu, const std::string& mem) { return json({{"type", "status"}, {"cpu", cpu}, {"memory", mem}}).dump() + "\n"; }
    static std::string git(const std::string& branch, const std::string& status) { return json({{"type", "git"}, {"branch", branch}, {"status", status}}).dump() + "\n"; }
    static std::string processes(const std::string& data) { return json({{"type", "processes"}, {"data", data}}).dump() + "\n"; }
};

class TerminalEngine {
    int master_fd; pid_t child_pid; int client_socket; std::mutex& send_mutex;
public:
    TerminalEngine(int client_sock, std::mutex& mutex) : master_fd(-1), child_pid(-1), client_socket(client_sock), send_mutex(mutex) {}
    void start() {
        struct winsize ws = {24, 80, 0, 0};
        child_pid = forkpty(&master_fd, NULL, NULL, &ws);
        if (child_pid == 0) {
            setenv("TERM", "xterm-256color", 1);
            execl("/bin/zsh", "zsh", "--login", NULL);
            _exit(1);
        }
        std::thread([this]() {
            char buffer[4096];
            while (true) {
                ssize_t n = read(master_fd, buffer, sizeof(buffer));
                if (n <= 0) break;
                std::string msg = Protocol::terminal(std::string(buffer, n));
                std::lock_guard<std::mutex> lock(send_mutex);
                if (send(client_socket, msg.c_str(), msg.length(), 0) < 0) break;
            }
        }).detach();
    }
    void write_input(const std::string& input) { if (master_fd != -1) write(master_fd, input.c_str(), input.length()); }
};

void handle_client(int client_socket) {
    std::mutex send_mutex;
    TerminalEngine terminal(client_socket, send_mutex);
    terminal.start();

    // 1. Process Monitor & Metrics Loop
    std::thread([client_socket, &send_mutex]() {
        while (true) {
            // Get Top 15 processes by CPU
            std::string proc_data = exec_shell("ps -Ao pid,pcpu,pmem,comm -r | head -n 16");
            std::string cpu = SystemMetrics::get_cpu();
            std::string mem = SystemMetrics::get_mem();
            std::string git_branch = exec_shell("git rev-parse --abbrev-ref HEAD 2>/dev/null || echo 'N/A'");
            
            std::string proc_msg = Protocol::processes(proc_data);
            std::string status_msg = Protocol::status(cpu, mem);
            std::string git_msg = Protocol::git(git_branch, "Active");

            {
                std::lock_guard<std::mutex> lock(send_mutex);
                send(client_socket, proc_msg.c_str(), proc_msg.length(), 0);
                send(client_socket, status_msg.c_str(), status_msg.length(), 0);
                send(client_socket, git_msg.c_str(), git_msg.length(), 0);
            }
            std::this_thread::sleep_for(std::chrono::seconds(2));
        }
    }).detach();

    char buffer[4096];
    while (true) {
        int n = read(client_socket, buffer, sizeof(buffer));
        if (n <= 0) break;
        try {
            auto j = json::parse(std::string(buffer, n));
            if (j["type"] == "command") terminal.write_input(j["command"].get<std::string>() + "\n");
        } catch (...) {}
    }
    close(client_socket);
}

int main() {
    system("adb reverse tcp:19090 tcp:19090 2>/dev/null");
    int server_fd = socket(AF_INET, SOCK_STREAM, 0);
    int opt = 1;
    setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
    struct sockaddr_in address;
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = inet_addr("127.0.0.1");
    address.sin_port = htons(PORT);
    if (bind(server_fd, (struct sockaddr *)&address, sizeof(address)) < 0) return 1;
    listen(server_fd, 5);
    std::cout << "[PROCESS MONITOR ACTIVE] Agent Listening on 127.0.0.1:" << PORT << std::endl;
    while (true) {
        int client = accept(server_fd, NULL, NULL);
        if (client >= 0) std::thread(handle_client, client).detach();
    }
    return 0;
}
