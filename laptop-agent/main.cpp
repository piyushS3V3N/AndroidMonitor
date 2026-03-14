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
#include <mutex>
#include <sstream>
#include <sys/stat.h>
#include <signal.h>
#include <deque>
#include <atomic>
#include "json.hpp"

#ifdef __APPLE__
    #include <CoreFoundation/CoreFoundation.h>
    #include <IOKit/IOKitLib.h>
    #include <IOKit/IOCFPlugIn.h>
    #include <IOKit/usb/IOUSBLib.h>
    #include <mach/mach_host.h>
    #include <sys/sysctl.h>
    #include <sys/param.h>
    #include <sys/mount.h>
#endif

using json = nlohmann::json;
#define PORT 19090
#define UDP_PORT 19091

std::atomic<bool> g_client_active(false);
std::vector<std::string> g_live_intel;
std::mutex g_data_mutex;

void setup_signals() { signal(SIGPIPE, SIG_IGN); }

std::string exec_shell(const std::string& cmd) {
    std::array<char, 128> buffer;
    std::string result;
    std::string silent_cmd = "{ " + cmd + "; } 2>/dev/null";
    std::unique_ptr<FILE, decltype(&pclose)> pipe(popen(silent_cmd.c_str(), "r"), pclose);
    if (!pipe) return "";
    while (fgets(buffer.data(), buffer.size(), pipe.get()) != nullptr) result += buffer.data();
    return result;
}

void discovery_broadcast() {
    int sock = socket(AF_INET, SOCK_DGRAM, 0);
    int broadcast = 1;
    setsockopt(sock, SOL_SOCKET, SO_BROADCAST, &broadcast, sizeof(broadcast));
    struct sockaddr_in addr;
    addr.sin_family = AF_INET;
    addr.sin_port = htons(UDP_PORT);
    addr.sin_addr.s_addr = INADDR_BROADCAST;
    std::string msg = "KAIZUKA_BEACON";
    while (true) {
        if (!g_client_active) sendto(sock, msg.c_str(), msg.length(), 0, (struct sockaddr*)&addr, sizeof(addr));
        std::this_thread::sleep_for(std::chrono::seconds(2));
    }
}

class SystemMetrics {
    static std::deque<double> cpu_h, mem_h, disk_h;
public:
    static json get_telemetry() {
        std::lock_guard<std::mutex> lock(g_data_mutex);
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
        int64_t tr; size_t ln = sizeof(tr); sysctlbyname("hw.memsize", &tr, &ln, NULL, 0); mem_total = (double)tr / (1024*1024*1024);
        vm_statistics64_data_t vm; mach_msg_type_number_t vmc = HOST_VM_INFO64_COUNT;
        if (host_statistics64(mach_host_self(), HOST_VM_INFO64, (host_info_t)&vm, &vmc) == KERN_SUCCESS) mem_used = (double)(vm.active_count + vm.wire_count) * 4096 / (1024*1024*1024);
        struct statfs st; if (statfs("/", &st) == 0) disk_p = 100.0 * (double)(st.f_blocks - st.f_bfree) / (double)st.f_blocks;
#endif
        cpu_h.push_back(cpu); if(cpu_h.size() > 30) cpu_h.pop_front();
        mem_h.push_back((mem_total > 0) ? (mem_used/mem_total)*100.0 : 0); if(mem_h.size() > 30) mem_h.pop_front();
        disk_h.push_back(disk_p); if(disk_h.size() > 30) disk_h.pop_front();
        return { {"type", "telemetry"}, {"cpu", cpu}, {"mem_used", mem_used}, {"disk_p", disk_p}, {"cpu_h", cpu_h}, {"mem_h", mem_h}, {"disk_h", disk_h},
                 {"net_io", exec_shell("netstat -ibn | grep -e 'en0' | head -1 | awk '{print \"IN: \"$7\" | OUT: \"$10}'")} };
    }
};
std::deque<double> SystemMetrics::cpu_h, SystemMetrics::mem_h, SystemMetrics::disk_h;

void intel_fetcher() {
    while (true) {
        std::string raw = exec_shell("curl -s --connect-timeout 2 --max-time 5 https://feeds.feedburner.com/TheHackersNews | grep -oEi '<title>[^<]+' | sed 's/<title>//g' | head -n 10");
        std::vector<std::string> news;
        std::stringstream ss(raw); std::string line;
        while (std::getline(ss, line)) if (line.length() > 5 && line.find("The Hacker News") == std::string::npos) news.push_back("SEC: " + line);
        {
            std::lock_guard<std::mutex> lock(g_data_mutex);
            if (!news.empty()) g_live_intel = news;
            else if (g_live_intel.empty()) g_live_intel.push_back("INTEL: Syncing threat-feed...");
        }
        std::this_thread::sleep_for(std::chrono::minutes(5));
    }
}

class ClientSession {
public:
    int socket; std::mutex send_mutex; std::atomic<bool> active;
    ClientSession(int s) : socket(s), active(true) {}
    void send_msg(const std::string& msg) {
        std::lock_guard<std::mutex> lock(send_mutex);
        if (active) {
            std::string frame = msg + "\n";
            if (send(socket, frame.c_str(), frame.length(), 0) <= 0) active = false;
        }
    }
};

void handle_client(int client_socket) {
    g_client_active = true;
    auto session = std::make_shared<ClientSession>(client_socket);
    std::cout << "[DISPATCH] Global Mission Control Linked." << std::endl;
    std::thread([session]() {
        int tick = 0; int intel_idx = 0;
        while (session->active) {
            session->send_msg(SystemMetrics::get_telemetry().dump());
            if (tick % 3 == 0) {
                session->send_msg(json({{"type", "processes"}, {"data", exec_shell("lsof -iTCP -sTCP:LISTEN -P -n | awk 'NR>1 {print $1 \" : \" $9}' | head -n 8")}}).dump());
                session->send_msg(json({{"type", "docker"}, {"data", exec_shell("docker ps --format '{{.Names}} [{{.Image}}] ({{.Status}})' | head -n 5")}}).dump());
                session->send_msg(json({{"type", "k8s_pods"}, {"data", exec_shell("kubectl get pods -A --no-headers -o custom-columns='NS:.metadata.namespace,POD:.metadata.name' | head -n 12")}}).dump());
                session->send_msg(json({{"type", "sec_radar"}, {"data", exec_shell("lsof -iTCP -sTCP:ESTABLISHED -P -n | awk 'NR>1 {print $1 \" | \" $9}' | head -n 8")}}).dump());
            }
            {
                std::lock_guard<std::mutex> lock(g_data_mutex);
                if (!g_live_intel.empty()) session->send_msg(json({{"type", "intel_news"}, {"message", g_live_intel[intel_idx++ % g_live_intel.size()]}}).dump());
            }
            tick++; std::this_thread::sleep_for(std::chrono::seconds(2));
        }
        g_client_active = false;
    }).detach();
    char b[1024]; while (session->active) if (recv(client_socket, b, sizeof(b), 0) <= 0) break;
    session->active = false; close(client_socket);
    std::cout << "[DISCONN] Hardware link dropped." << std::endl;
}

int main() {
    setup_signals();
    std::thread(intel_fetcher).detach();
    std::thread(discovery_broadcast).detach();
    int server_fd = socket(AF_INET, SOCK_STREAM, 0);
    int opt = 1; setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
    struct sockaddr_in addr; addr.sin_family = AF_INET; addr.sin_addr.s_addr = INADDR_ANY; addr.sin_port = htons(PORT);
    bind(server_fd, (struct sockaddr*)&addr, sizeof(addr));
    listen(server_fd, 5);
    std::cout << "[KAIZUKA MASTER ONLINE]" << std::endl;
    while (true) {
        int c = accept(server_fd, NULL, NULL);
        if (c >= 0) std::thread(handle_client, c).detach();
    }
    return 0;
}
