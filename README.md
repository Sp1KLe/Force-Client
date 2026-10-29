# ForceCLIENT (ForceC)

ForceCLIENT is an Android VPN client project with a Material 3 interface.

## Current milestone
- Kotlin + Jetpack Compose and Material 3
- Dark/light theme switch
- Home, servers, and settings navigation
- Import local configuration files
- Import a subscription by HTTPS URL
- Subscription downloads are limited to 1 MiB and must return supported VLESS, VMess, Trojan, Shadowsocks, or JSON configuration data

## Build
Open this repository in Android Studio with Android SDK installed, sync Gradle, then run the `app` configuration.

## Network engine
The build workflow downloads the pinned official `libXray.aar` release and includes it in the Android app. `XrayEngine.kt` provides a bridge to Xray-core for share-link conversion, engine version checks, config validation, and start/stop requests. The Android system VPN tunnel and packet forwarding are a separate integration stage; until that is implemented and tested, the app must not claim that device traffic is protected by a VPN.

## Identity
App name: ForceCLIENT  
Short name: ForceC  
Design: Material 3, dark chocolate and warm beige.
