# KAIZUKA DEVSECOPS BOARD
### Professional High-Density Observability Sidecar

Kaizuka.OS is a technical mission-control dashboard designed to transform an Android tablet into a high-fidelity telemetry station. It provides real-time system, Kubernetes, and security intelligence in a high-density tactical interface.

## System Capabilities
- **Master Telemetry Stack**: Vertically-stacked sparklines for CPU, RAM, and Disk health monitoring.
- **Kubernetes Operations Hub**: Multi-namespace pod discovery with interactive log hot-swapping.
- **Docker Infrastructure Radar**: Container monitoring with image identity and status tracking.
- **Network Service Observer**: Real-time tracking of listening ports and established TCP connections.
- **Cyber Intelligence Feed**: Asynchronous security headline streaming from global threat feeds.
- **Ergonomic Data Streams**: High-legibility monospace typography with tactical zebra-striping.

## Connectivity Architecture (Triple-Path Fusion)
The system employs a multi-path connectivity engine to ensure persistent synchronization across varied hardware environments:
1. **Direct Hardware (AOA)**: Pure USB-C communication using Android Open Accessory mode. 
2. **Auto-Discovery (UDP)**: Automatic laptop identification via local network UDP beaconing.
3. **Legacy Bridge (ADB)**: Automated background maintenance of ADB reverse tunnels as a secondary channel.

## Installation and Deployment

### 1. Build the Laptop Agent
The agent is compatible with macOS, Native Linux, and Windows WSL.
```bash
./build_agent.sh
```

### 2. Build the Android Dashboard
Requires Android SDK with Java 17+.
```bash
./gradlew assembleDebug
```

### 3. Deployment Procedure
1. Execute the agent binary: `./devcompanion`
2. Install the generated APK on the target Android device.
3. Establish a physical connection via USB-C or verify local network proximity.
4. The dashboard will synchronize and display **HW.LINK.OK**.

## Technical Specifications
- **Agent**: Lightweight C++11 implementation utilizing non-blocking tiered streaming.
- **Mobile**: Android SDK (Java) with `MPAndroidChart` integration for real-time visualization.
- **Data Protocol**: Bidirectional JSON frames over TCP and USB Bulk transfers.

## License
Distributed under the MIT License. See `LICENSE` for more information.

---
**DEVELOPER OPERATOR: PIYUSH PARASHAR**  
