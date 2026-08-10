# Cruze

A motorcycle route planner and group-riding app for Android. It finds the twisty road
instead of the fast one, guides you along it by voice, and keeps your group together.

**No accounts. No API keys. No paid services.** Every backing service is free and keyless.

## What it does

**Routing**
- Curvy-road routing. Candidate routes are scored on curvature measured from the road
  geometry, and the twistiest is chosen within a time budget you set with Fast / Balanced /
  Curvy. Calibrated against real roads: Tail of the Dragon scores 479°/mi, the Blue Ridge
  Parkway 335, an interstate 7.
- It refuses a detour that is not worth it. A back road that is barely less straight but an
  hour longer loses to the highway.
- Round trips: out one way, home another, via automatically placed waypoints.
- Turn-by-turn voice guidance that ducks your music rather than fighting it, with cue
  distances that scale with speed. Back-to-back turns are spoken together.
- Off-route detection and rerouting that needs repeated evidence, so one bad GPS fix does
  not send you round the houses.
- GPX import and export.

**Group riding**
- Start a ride, share a six-character code or its QR. Everyone sees everyone.
- Live roster: distance from you, distance off the leader, battery, role, connection.
- Group spread and an alert when someone falls behind a distance you choose.
- The leader pushes their route to the group and everyone receives it identically —
  same line, same turns, same spoken words.
- One-tap messages ("fuel stop", "pulling over", "hazard ahead") that are spoken aloud
  and popped up on screen, because nobody should read text at speed.
- Rider-down detection: a hard impact *and* thirty seconds of stillness *and* the phone
  lying over, then a full-screen countdown with one enormous cancel button. It alerts your
  group and nobody else. No SMS, no emergency services.

**The rest**
- Rain radar overlay and weather warnings sampled along your route.
- Ride recording with distance and moving time, exportable as GPX.
- A garage: several bikes, service schedules that warn before they fall due, a fuel log
  with economy and cost per mile, and CSV export. Recorded rides add to the odometer.
- Dark, red-on-black, with 56dp touch targets sized for gloves.

## Services it uses

| Purpose | Service | Key needed |
|---|---|---|
| Routing and turn instructions | [Valhalla](https://valhalla1.openstreetmap.de) (FOSSGIS) | none |
| Map tiles | OpenStreetMap, CARTO, OpenTopoMap, Esri | none |
| Place search | [Nominatim](https://nominatim.openstreetmap.org) | none |
| Rain radar | [RainViewer](https://rainviewer.com) | none |
| Weather warnings | [US National Weather Service](https://api.weather.gov) | none |
| Group position relay | [ntfy.sh](https://ntfy.sh) | none |

All of these are community-run and free. Cruze identifies itself properly and keeps request
volume low, which is the price of using them.

## Building

Needs JDK 21 and the Android SDK (compileSdk 35).

```bash
./gradlew assembleDebug          # build
./gradlew testDebugUnitTest      # tests
./gradlew installDebug           # install to a connected device
```

Some tests exercise the live routing service. They skip themselves when there is no
network rather than failing.

## Downloading a build

Every merge to `main` publishes an installable APK under
[Releases](../../releases). Download the `.apk` and allow installation from unknown
sources when prompted.

Builds are signed with Android's shared debug key. They install and run fine, but cannot go
on Play and will not upgrade in place over a differently signed build.

## Things worth knowing before you rely on it

- **The group relay is a free public service and it rate-limits publishing.** Positions are
  sent at most every three seconds and back off when throttled. A large group in heavy
  traffic may see delayed positions. Settings lets you point Cruze at your own ntfy server,
  which removes the limit but costs you a server.
- **A group's join code is its only protection.** Anyone who learns the code can see the
  group's positions while the ride is running. Codes are per-ride and sharing stops the
  moment the ride ends. No account details or personal data are ever sent — only a display
  name and a position.
- **Rider-down detection is a phone with an accelerometer, not a safety system.** It will
  miss crashes and it will occasionally cry wolf. Do not rely on it.
- **Routing and search need a data connection.** Offline maps and offline routing are not
  built yet.
- Map data is OpenStreetMap, so route quality follows how well your area is mapped.

## Licence and attribution

Map data © OpenStreetMap contributors, [ODbL](https://www.openstreetmap.org/copyright).
Tiles © CARTO, © OpenTopoMap (CC-BY-SA), Esri. Routing by Valhalla. Radar by RainViewer.
