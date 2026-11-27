# How this was verified

The app was built without access to a phone or an emulator, so each part was checked as far
as it could be without one. This page says what was checked, how, and what was not.

## Not verified

- **Nothing has run on a physical Android device.** The Android-specific code (Wi-Fi and
  connectivity collectors, the ICMP pinger, permission flows, the scan coordinator) compiles
  against the real Android 17 SDK and passes lint, and the app launches in Robolectric, but
  its behaviour on real hardware is untested. This is the largest open risk.
- **DNS timing against real resolvers.** The build sandbox intercepts DNS and answers every
  query itself, so the benchmark could only be tested against a local responder.
- **Which `ping` program a given phone ships.** The parser handles both iputils and toybox
  output. It is only used as a fallback and for the first-hops check.
- **The thresholds in the analysis** are judgement calls from common networking practice.

## Verified against real services

| What | How | Result |
| --- | --- | --- |
| Speed test endpoints | Requests to `speed.cloudflare.com` | `/__down?bytes=N` returns exactly N bytes up to 25 MB and answers HTTP 403 at 100 MB and above, so requests are capped at 25 MB. `/__up` accepted 25 MB. Client metadata arrives in response headers. |
| Speed test engine | `LiveNetworkTest`, a full run against Cloudflare from a datacentre line | Completed with no errors or refusals: about 1.6 Gbps each way over eight streams, idle and loaded latency all reported. |
| Latency calculation | Read from the published `@cloudflare/speedtest` client (v1.17.0) | Latency is time to first byte minus the server time in `Server-Timing` (`cfSpeed*` entries summed). This engine does the same. |

## Verified against real implementations

Real daemons were started in the build sandbox and the app's own code was run against them
(`RealDaemonTest`, enabled with `-PdaemonHost=<address>`).

| Protocol | Implementation | What was confirmed |
| --- | --- | --- |
| NetBIOS node status | Samba `nmbd` | The request this code builds was answered; computer name and workgroup parsed correctly. The reply is kept as a test fixture. |
| mDNS / DNS-SD | Avahi, over real multicast | Services, instance name, host name and TXT records were discovered, including a service type found only by following service enumeration. |
| SSDP and UPnP gateway | miniupnpd 2.3.4 | Multicast search found it; description parsed; `GetExternalIPAddress`, `GetStatusInfo`, `GetCommonLinkProperties` and the byte counters returned the configured values. Replies are kept as fixtures. |

To reproduce, start the three daemons on one interface with:

- Samba: `netbios name = TESTPC`, `workgroup = HOMELAB`
- Avahi: host name `testhost`, a static service group named `Living Room TV` publishing
  `_googlecast._tcp` (TXT `fn=Living Room TV`, `md=Chromecast Ultra`) and `_customthing._tcp`
- miniupnpd: `friendly_name=Test Router`, `manufacturer_name=TestCorp`, `model_name=TR-1000`,
  `ext_ip=93.184.216.34`, `bitrate_down=300000000`, `bitrate_up=20000000`

then run `./gradlew :core:test -PdaemonHost=<that interface's address> --tests '*RealDaemonTest*'`.

## Verified against the kernel and platform sources

| What | How |
| --- | --- |
| Unprivileged ICMP "ping sockets" | Exercised on Linux over loopback: the kernel rewrites the identifier to the socket's port, computes the checksum and returns the reply without an IP header. The pinger matches replies on sequence number and payload accordingly. |
| Apps may use ping sockets on Android | `init.rc` sets `net.ipv4.ping_group_range` to all groups; the SELinux policy allows `netdomain` to create `icmp_socket`; libcore's own `InetAddress.isReachable` uses the same socket type. Read from AOSP's `init.rc` and LineageOS's copies of the policy and libcore, since the AOSP host was not reachable. |
| `ping` output formats | iputils output captured from a real run; toybox output built from its source's format strings. |
| Wi-Fi capability strings | Token names taken from the Android Wi-Fi module's `InformationElementUtil`. |
| UPnP message formats | Taken from miniupnpc, then confirmed against miniupnpd as above. |
| Android APIs and permissions | The code compiles against the installed Android 17 SDK and passes lint's API-level check; the main Wi-Fi, connectivity and socket APIs were also looked up in the SDK's `api-versions.xml`. Permission behaviour (Wi-Fi scanning, Android 17 local network permission, low-latency Wi-Fi lock) was read from the Android developer documentation. |

## Verified by simulation

- **Speed measurement accuracy.** A local server that behaves like a line of fixed capacity
  shared by all connections. Download measured 40.0 on a 40 Mbps line and 319.8 on a
  320 Mbps line; upload measured 12.0 on a 12 Mbps line; a simulated 120 ms of added delay
  under load was reported as such.
- **Why upload counts confirmed bytes.** The first version counted upload bytes as they were
  written and measured 17.6 Mbps on that 12 Mbps line, because data queued in buffers was
  counted too. It now counts only bytes the server has acknowledged.
- **Analysis rules.** Scenario tests cover a healthy network, weak Wi-Fi, a bad line,
  crowded channels, bufferbloat, slow and dead DNS, double NAT, a 100 Mbps WAN port,
  background traffic, mobile data, VPN and partial scans.
- **Screens.** Every screen is rendered on the JVM from sample data
  (`./gradlew :app:recordRoborazziDebug`) and was looked at. A smoke test launches the real
  activity and opens every tab.

## Reference figures

These come from web search results quoting the sources; the pages themselves could not be
opened from the build environment, so treat them as second-hand.

- Bufferbloat grades (added latency under 5 ms A+, 30 A, 60 B, 200 C, 400 D, otherwise F):
  the scale several tools publish and attribute to Waveform's test.
- Streaming: Netflix's help page recommends 5 Mbps for 1080p and 15 Mbps for 4K.
- Video calls: Zoom lists about 3.8 Mbps up and 3.0 Mbps down for 1080p group calls.
