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
#include <mutex>
#include <sstream>
#include <sys/stat.h>
#include <sys/mount.h>
#include <signal.h>
#include <deque>
#include <atomic>
#include <algorithm>
#include "json.hpp"

#ifdef __APPLE__
    #include <util.h>
    #include <termios.h>
    #include <sys/ioctl.h>
    #include <sys/sysctl.h>
    #include <mach/mach_host.h>
    #include <mach/mach_init.h>
    #include <IOKit/IOKitLib.h>
    #include <IOKit/usb/IOUSBLib.h>
    #include <CoreFoundation/CoreFoundation.h>
#endif

using json = nlohmann::json;
#define PORT 19090

void setup_signals() { signal(SIGPIPE, SIG_IGN); }

std::string exec_shell(const std::string& cmd) {
    std::array<char, 1024> buffer;
    std::string result;
    std::string silent_cmd = "{ " + cmd + "; } 2>/dev/null";
    std::unique_ptr<FILE, decltype(&pclose)> pipe(popen(silent_cmd.c_str(), "r"), pclose);
    if (!pipe) return "";
    while (fgets(buffer.data(), buffer.size(), pipe.get()) != nullptr) result += buffer.data();
    return result;
}

class SystemMetrics {
    static std::deque<double> cpu_h, mem_h, disk_h;
public:
    static json get_telemetry() {
        double cpu = 0, mem_used = 0, mem_total = 0, disk_p = 0;
#ifdef __APPLE__
        host_cpu_load_info_data_t cpu_load;
        mach_msg_type_number_t count = HOST_CPU_LOAD_INFO_COUNT;
        if (host_statistics(mach_host_self(), HOST_CPU_LOAD_INFO, (host_info_t)&cpu_load, &count) == KERN_SUCCESS) {
            static unsigned long long pv_u=0, pv_s=0, pv_i=0, pv_n=0;
            unsigned long long u = cpu_load.cpu_ticks[CPU_STATE_USER], s = cpu_load.cpu_ticks[CPU_STATE_SYSTEM], i = cpu_load.cpu_ticks[CPU_STATE_IDLE], n = cpu_load.cpu_ticks[CPU_STATE_NICE];
            unsigned long long tot = (u-pv_u)+(s-pv_s)+(i-pv_i)+(n-pv_n);
            cpu = (tot == 0) ? 0 : 100.0 * (double)((u-pv_u)+(s-pv_s)) / (double)tot;
            pv_u=u; pv_s=s; pv_i=i; pv_n=n;
        }
        int64_t total_ram; size_t len = sizeof(total_ram);
        sysctlbyname("hw.memsize", &total_ram, &len, NULL, 0);
        mem_total = (double)total_ram / (1024*1024*1024);
        vm_statistics64_data_t vm_stats;
        mach_msg_type_number_t vm_count = HOST_VM_INFO64_COUNT;
        if (host_statistics64(mach_host_self(), HOST_VM_INFO64, (host_info_t)&vm_stats, &vm_count) == KERN_SUCCESS) {
            mem_used = (double)(vm_stats.active_count + vm_stats.wire_count) * PAGE_SIZE / (1024*1024*1024);
        }
        struct statfs st;
        if (statfs("/", &st) == 0) disk_p = 100.0 * (double)(st.f_blocks - st.f_bfree) / (double)st.f_blocks;
#endif
        cpu_h.push_back(cpu); if(cpu_h.size() > 30) cpu_h.pop_front();
        mem_h.push_back((mem_total > 0) ? (mem_used/mem_total)*100.0 : 0); if(mem_h.size() > 30) mem_h.pop_front();
        disk_h.push_back(disk_p); if(disk_h.size() > 30) disk_h.pop_front();

        return {
            {"type", "telemetry"}, {"cpu", cpu}, {"mem_used", mem_used}, {"disk_p", disk_p}, 
            {"cpu_h", cpu_h}, {"mem_h", mem_h}, {"disk_h", disk_h},
            {"net_io", exec_shell("netstat -ibn | grep -e 'en0' | head -1 | awk '{print \"IN: \"$7\" | OUT: \"$10}'")}
        };
    }
};
std::deque<double> SystemMetrics::cpu_h, SystemMetrics::mem_h, SystemMetrics::disk_h;

std::vector<std::string> fetch_real_intel() {
    std::vector<std::string> news;
    std::string raw = exec_shell("curl -s https://feeds.feedburner.com/TheHackersNews | grep -oEi '<title>[^<]+' | sed 's/<title>//g' | head -n 15");
    std::stringstream ss(raw);
    std::string line;
    while (std::getline(ss, line)) {
        if (!line.empty() && line.find("The Hacker News") == std::string::npos) news.push_back("SEC: " + line);
    }
    if (news.empty()) news.push_back("INTEL: Synchronizing threat-feed...");
    return news;
}

class ClientSession {
public:
    int socket; std::mutex send_mutex; std::atomic<bool> active;
    std::string selected_pod; std::string selected_ns;
    ClientSession(int s) : socket(s), active(true) {}
    void send_msg(const std::string& msg) {
        std::lock_guard<std::mutex> lock(send_mutex);
        if (active) send(socket, msg.c_str(), msg.length(), 0);
    }
};

void stream_pod_logs(std::shared_ptr<ClientSession> session, std::string ns, std::string pod) {
    session->selected_ns = ns; session->selected_pod = pod;
    std::thread([session, ns, pod]() {
        std::string cmd = "kubectl logs -f " + pod + " -n " + ns + " --tail=50 2>/dev/null";
        FILE* pipe = popen(cmd.c_str(), "r");
        if (!pipe) return;
        char buffer[1024];
        while (session->active && session->selected_pod == pod && fgets(buffer, sizeof(buffer), pipe)) {
            session->send_msg(json({{"type", "k8s_log"}, {"message", "[" + ns + "/" + pod + "] " + std::string(buffer)}}).dump() + "\n");
        }
        pclose(pipe);
    }).detach();
}

void handle_client(int client_socket) {
    auto session = std::make_shared<ClientSession>(client_socket);
    std::thread([session]() {
        int news_timer = 0; std::vector<std::string> live_news = fetch_real_intel(); int story_idx = 0;
        while (session->active) {
            session->send_msg(SystemMetrics::get_telemetry().dump() + "\n");
            session->send_msg(json({{"type", "processes"}, {"data", exec_shell("lsof -iTCP -sTCP:LISTEN -P -n | awk 'NR>1 {print $1 \" : \" $9}' | head -n 8")}}).dump() + "\n");
            session->send_msg(json({{"type", "docker"}, {"data", exec_shell("docker ps --format '{{.Names}} [{{.Image}}] ({{.Status}})' | head -n 5")}}).dump() + "\n");
            session->send_msg(json({{"type", "k8s_pods"}, {"data", exec_shell("kubectl get pods -A --no-headers -o custom-columns='NS:.metadata.namespace,POD:.metadata.name' | head -n 12")}}).dump() + "\n");
            session->send_msg(json({{"type", "sec_radar"}, {"data", exec_shell("lsof -iTCP -sTCP:ESTABLISHED -P -n | awk 'NR>1 {print $1 \" | \" $9}' | head -n 8")}}).dump() + "\n");
            if (++news_timer % 150 == 0) live_news = fetch_real_intel(); 
            session->send_msg(json({{"type", "intel_news"}, {"message", live_news[story_idx++ % live_news.size()]}}).dump() + "\n");
            std::this_thread::sleep_for(std::chrono::seconds(2));
        }
    }).detach();

    char buffer[2048];
    while (session->active) {
        ssize_t n = read(client_socket, buffer, sizeof(buffer));
        if (n <= 0) break;
        try {
            auto j = json::parse(std::string(buffer, n));
            if (j["type"] == "select_pod") stream_pod_logs(session, j["namespace"], j["pod"]);
        } catch (...) {}
    }
    session->active = false;
    close(client_socket);
}

int main() {
    setup_signals();
    int server_fd = socket(AF_INET, SOCK_STREAM, 0);
    int opt = 1; setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
    struct sockaddr_in addr; addr.sin_family = AF_INET; addr.sin_addr.s_addr = INADDR_ANY; addr.sin_port = htons(PORT);
    bind(server_fd, (struct sockaddr*)&addr, sizeof(addr));
    listen(server_fd, 5);
    std::cout << "[KAIZUKA HARDWARE LINK ACTIVE]" << std::endl;
    while (true) {
        int c = accept(server_fd, NULL, NULL);
        if (c >= 0) std::thread(handle_client, c).detach();
    }
    return 0;
}
