# NOC Monitor

Android network operations center for **MikroTik RouterOS**, with a separate provider stack for Ubiquiti UniFi, EdgeOS, and airOS.

This is a working management client, not a UI mock. In **REAL** mode every counter, session, and command comes from the device. The app never invents traffic. **DEMO** mode is optional, labeled on every screen, and isolated from real devices.

## What it does

- Add MikroTik routers by IP/hostname, username, password, and API port
- Test Connection against the live device
- RouterOS **API** (TCP 8728), **API-SSL** (TCP 8729), and **REST** (HTTPS)
- System identity, model, version, uptime, CPU, RAM, temperature, storage
- Interfaces with running/enabled state and RX/TX byte and packet counters
- Live traffic graphs with local history: 5 minutes, 1 hour, 6 hours, 24 hours
- Highest traffic reached per device
- Active PPPoE sessions and disconnect (real `/ppp/active/remove`) after confirmation
- Enable/disable interfaces (real `/interface/set`) after confirmation
- Reboot (real `/system/reboot`) with double confirmation
- Every command shows the device result or the actual error
- IP, DHCP, ARP, routes, logs
- Dashboard: totals, online/offline, alerts, total traffic, highest-traffic device
- Arabic RTL and English LTR
- Dark NOC theme

Port **9 is rejected**. It is never used as a management port.

## Security

- Passwords live in `EncryptedSharedPreferences` backed by the Android Keystore
- Passwords are never written to logs
- Timeouts, connection refused, unknown host, TLS failures, authentication failures, and permission errors are mapped to explicit messages

## Ubiquiti

UniFi, EdgeOS, and airOS are **separate adapters**. The UI only offers commands that family actually supports. Unsupported operations render as **Unsupported**, not as fake buttons.

## Requirements

- Android 8.0 (API 26)+
- JDK 17+
- Android SDK 35

## Build the APK

Release (signed, `com.noc.monitor` 1.0.0):

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew :app:assembleRelease
```

Gradle output: `app/build/outputs/apk/release/app-release.apk`  
Checked-in copy: `dist/noc-monitor-1.0.0-release.apk`

Debug:

```bash
./gradlew :app:assembleDebug
```

## Lab simulator (not DEMO)

The `protocol` module includes a RouterOS **binary API** lab simulator that speaks the real wire protocol. Use it to exercise the client without a physical router. Treat that endpoint as a lab box, not as DEMO mode.

```bash
./gradlew :protocol:test
```

Functional tests cover: add/connect, system info, interfaces, live counters, PPPoE, a safe interface disable, authentication failure, connection refused, REST 401, and port 9 rejection.

## REAL vs DEMO

| Mode | Data source |
|------|-------------|
| REAL | Live RouterOS API / API-SSL / REST (or the matching Ubiquiti API) |
| DEMO | Local sample provider, yellow **DEMO** banner, reboot disabled |

## Default ports

| Transport | Default port |
|-----------|----------------|
| RouterOS API | 8728 |
| RouterOS API-SSL | 8729 |
| REST / UniFi / EdgeOS / airOS | 443 |

Never 9.
