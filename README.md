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

## VPN status
The VPN engine is not integrated yet. Importing a subscription only downloads and stores configuration data; it does not connect a VPN tunnel. Server ping and subscription refresh controls are also not fully implemented.

## Identity
App name: ForceCLIENT  
Short name: ForceC  
Design: Material 3, dark chocolate and warm beige.
