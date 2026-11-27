# How this was verified

The app was built without access to a phone or an emulator, so each part was checked as far
as it could be without one. This page says what was checked, how, and what was not.

## Seen on real phones

The owner ran the connection test (version 1.1.0) on two phones from the same spot, against
a two-unit mesh, and sent the result screens. That is the only part run on hardware so far.

| Phone | What was seen |
| --- | --- |
| Samsung Galaxy A17 5G | The app started, Android's approval step was passed and the test ran to the end across three access point radios. Silent retries were counted, so this phone does send the supplicant status broadcast. Two requests failed with "Failed" and no stage: Android's failure report carried its "unknown" code. From 1.2.0 the app names the stage from the steps it saw in that case. |
| Motorola | The test ran to the end across four radios; all 12 requests connected. |

The two results also confirmed, for that mesh, the assumption behind grouping radios into
boxes: each unit's 2.4 GHz and 5 GHz radio had addresses one apart, and the two units had
unrelated prefixes. And they showed the test doing its job: one phone was turned away by a
radio that the other phone joined without trouble from the same spot.

The owner then ran version 1.2.0 on the Galaxy A17 5G and shared the full log (four test runs
and some everyday use). It showed:

- Every connection step now carries the name, address and channel of the access point being
  tried, so that part of 1.2.0 works on Samsung's Wi-Fi software.
- One radio accepted the phone and cut the link 24 to 55 ms later, before the key exchange
  began, on 12 of 12 tries, while three other radios let it in on 9 of 9. The same pattern
  runs through the earlier runs: 43 such tries in all. The app called this a failed key
  exchange, which is not what happened; 1.3.0 names it as being dropped right after joining.
- The time shown as "to connect" included up to 34 s that Android spent looking for a radio
  before its first try, which made one radio look slow when it was not. 1.3.0 measures the
  two apart.
- A signal reading of 0 dBm was logged just after connecting (no reading yet). 1.3.0 leaves
  such readings out.

That log, with network names, addresses and the date replaced and the timing untouched, is
kept as `core/src/test/resources/real-run-galaxy-a17.log`, and `RealPhoneLogTest` checks the
analysis against it.

Not yet seen on a phone: what 1.3.0 adds on screen (the radio details, the new step name),
the connection watch over a longer period, the background service, and every other tab.

## Not verified

- **Most of the app has not run on a physical Android device.** Apart from what is listed
  above, the Android-specific code (Wi-Fi and connectivity collectors, the ICMP pinger,
  permission flows, the scan coordinator, the event monitor's longer-term behaviour and the
  background watch service) compiles against the real Android 17 SDK and passes lint, and the
  app launches in Robolectric, but its behaviour on real hardware is untested. This is the
  largest open risk.
- **The connection test and connection watch on a real phone.** What Android does with a
  requested connection was read from the source of Android's own Wi-Fi module (see the table
  further down), and the app's side was exercised in a simulated Android in which the test
  plays the part of the system. Phone makers ship their own Wi-Fi software, Samsung included,
  and it may differ from that source. Not known until it runs on a phone:
  - whether a given phone sends the supplicant status broadcast that the detailed steps and
    the silent-retry count rely on (Android's documentation marks it deprecated; the current
    source still sends it, and only for the phone's main Wi-Fi interface). The Galaxy A17 5G
    does; other phones are unknown;
  - whether a given phone names the access point while an attempt is still in progress, which
    is what lets a failed attempt be pinned on one access point;
  - what the maker's approval dialog looks like and whether it behaves like the stock one;
  - whether a foreground service really keeps Wi-Fi status updates arriving with the app
    closed on that phone, given its battery management;
  - how quickly Wi-Fi rejoins the saved network after a test.
- **Which radios share a box.** Radios are grouped when their addresses agree in the first
  five bytes, lie within eight of each other in the last, and are on different channels. That
  is how makers usually number them, and it held for the one mesh seen so far, but it is a
  convention, not a rule.
- **The one-second rule for "dropped".** A link cut within one second of the access point
  accepting the phone, with no key exchange step seen, is called dropped; later than that it
  is called a key exchange that did not complete. On the one phone seen, drops came within a
  tenth of a second, so the margin is wide, but the figure is a judgement.
- **The 400 ms rule.** A request that ends in under 400 ms with no attempt seen is taken to
  have been refused outright by Android (which it does when the app is not on screen). The
  figure is a judgement: faster than a person can answer a dialog or a scan can complete.
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

### How Android handles a requested Wi-Fi connection

The connection test is built on `WifiNetworkSpecifier`. Its documented behaviour was read
from the Android developer reference; the details below are not in the documentation and
were read from the Wi-Fi module's source (`WifiNetworkFactory.java`, `WifiConfiguration.java`,
`WifiNetworkSpecifier.java`, `WifiManager.java`, `ClientModeImpl.java`) in LineageOS's copy of AOSP
`packages/modules/Wifi`, branch `lineage-23.0`, because the AOSP host was not reachable.

| Behaviour the app relies on | Where it was read |
| --- | --- |
| A request is only accepted from an app or service in the foreground, and is rejected at once when Wi-Fi is off. A rejected request is released as unfulfillable, which the app receives as `onUnavailable`. | `acceptRequest`, `needNetworkFor`, `cleanupActiveRequest` |
| A failed connection is retried up to three more times before failure is reported, except for a wrong password, which is not retried. | `USER_SELECTED_NETWORK_CONNECT_RETRY_MAX`, `handleNetworkConnectionFailure` |
| The user's approval is remembered per app. For password-protected networks a later request for any access point with the same name and security type skips the dialog; for open networks only the exact access point approved before does. | `isAccessPointApprovedInInternalApprovalList` |
| The connection is made through a temporary network entry of the app's own, pinned to the requested access point and separate from the phone's saved network of the same name, so the test uses the password typed in and leaves the saved network alone. The entry is removed when the request is released. | `handleConnectToNetworkUserSelectionInternal` (`ephemeral`, `shared = false`, `fromWifiNetworkSpecifier`, `BSSID`), `WifiConfiguration.getProfileKey`, `disconnectAndRemoveNetworkFromWifiConfigManager` |
| The test connection normally uses a randomized hardware address, whatever the saved network is set to: the request API has no setting for it, the entry is created with the default (automatic), and any setting other than "none" is randomized when the phone has MAC randomization switched on. | `WifiNetworkSpecifier.Builder` (no such setter), `WifiConfiguration.macRandomizationSetting`, and the connect path in `ClientModeImpl` (`configureRandomizedMacAddress` unless `RANDOMIZATION_NONE`) |
| While an attempt is in progress, Android's Wi-Fi status holds the name, address and channel of the access point being tried, and clears them when the attempt ends. The app reads it at each step, which is how a failed attempt is attributed to an access point. | `ClientModeImpl.handleSupplicantStateChange` |
| Requesting one band is not implemented, so radios are targeted by address instead. | `TODO(b/188021807)` in `handleConnectToNetworkUserSelectionInternal` |
| While it waits for approval or looks for the access point, scanning pauses when the screen turns off, so the app keeps the screen on during the test. | `handleScreenStateChanged` |
| Normal automatic joining is switched back on when the request ends. | `removeClientModeManagerIfNecessary` |
| Passwords must be ASCII or the builder throws; the app checks this before starting. | `WifiNetworkSpecifier.Builder.setWpa2Passphrase`, `setWpa3Passphrase` |
| Failure reasons reported to the app from Android 14: unknown, association, authentication, IP provisioning, not found, no response, and (Android 16) user reject. | `internalConnectionEventToLocalOnlyFailureCode`, `WifiManager.STATUS_LOCAL_ONLY_CONNECTION_FAILURE_*`; the app's mapping is asserted against the SDK constants in `ConnectionTesterTest` |
| Name and extras of the supplicant status broadcast, and the authentication error codes. | `WifiManager`; asserted against the SDK constants in `WifiEventMonitorTest` |

Foreground service rules (the `connectedDevice` type and the permissions it needs, and that
status broadcasts are held back from apps that are not active) were read from the Android
developer documentation.

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
- **Connection attempts from Wi-Fi events.** Scenario tests rebuild attempts from event
  sequences: clean connections, failures at each step, silent retries, moves between access
  points, events arriving out of order between the two status channels, phones that report
  only coarse states, and the tail of an unrelated attempt at the start of a test window.
- **Connection test conclusions.** Scenario tests cover everything working, nothing working
  (by reason), one band failing, some access points failing, flaky everywhere, success only
  after retries, and tests that were declined or cut short.
- **The connection tester inside a simulated Android** (Robolectric). The test plays the
  system: it answers each connection request and sends the Wi-Fi steps a phone would. This
  covers a run across two access points with a clean success and a failure after silent
  retries, a request declined in the dialog, a request refused outright, stopping midway, and
  every reason for refusing to start. It checks the tester's logic, not a real phone's replies.
- **The event recorder inside a simulated Android.** Broadcasts built by hand, and one sent
  through the simulated system, are recorded correctly; the log survives a restart.
- **The Connect tab's logic.** A simulated Wi-Fi scan goes in; the networks offered, the
  access points ticked by default, and the conditions for starting are checked, along with
  refusing to start and stopping.
- **The background watch service inside a simulated Android.** It enters the foreground with
  its notification and Stop button, and clears its state when stopped. Leaving and reopening
  the app is noted in the log; a rotation is not.
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
