# WayIsPer

WayIsPer is an offline-first Android messaging application that enables communication between nearby devices without the need for an internet connection. By leveraging Google's Nearby Connections API, Bluetooth, and Wi-Fi, WayIsPer allows users to discover, connect, and chat in environments where cellular or internet access is unavailable.

## Features

- **Offline Messaging**: Chat with nearby users using Bluetooth and Wi-Fi Direct.
- **Local Discovery**: Automatically find and connect to other devices running WayIsPer in your vicinity.
- **Privacy-Focused**: Communication happens directly between devices without passing through a central server.
- **Adaptive UI**: Modern Android interface with support for Material Design 3.

## Tech Stack

- **Language**: Kotlin
- **Platform**: Android
- **Networking**: Google Nearby Connections API
- **UI**: XML Layouts with Material Components
- **Architecture**: Modern Android development practices

## Getting Started

### Prerequisites

- Android Studio Flamingo or newer
- Android device running API 24 (Nougat) or higher
- Bluetooth and Location services enabled on your device

### Installation

1. Clone the repository:
   ```bash
   git clone https://github.com/mateenahmedintwish/WayIsPer.git
   ```
2. Open the project in Android Studio.
3. Sync the project with Gradle files.
4. Build and run the application on your Android device.

## Usage

1. Open the app on two or more nearby devices.
2. Grant the necessary permissions (Bluetooth, Location, Wi-Fi).
3. Use the **EntryActivity** to discover or create a room.
4. Once connected, start chatting in the **ChatActivity**.

## Permissions

WayIsPer requires the following permissions to function:
- `BLUETOOTH` & `BLUETOOTH_ADMIN`: For legacy device discovery.
- `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, & `BLUETOOTH_SCAN`: For modern Bluetooth interactions (Android 12+).
- `ACCESS_FINE_LOCATION`: Required by the system for Bluetooth and Wi-Fi discovery.
- `NEARBY_WIFI_DEVICES`: For Wi-Fi-based connections without location tracking (Android 13+).

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
