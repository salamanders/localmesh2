# LocalMesh2 Project

## Project Overview

This is an Android application that creates a peer-to-peer mesh network using the Google Nearby
Connections API. The application, named "localmesh2", allows devices to discover and connect to each
other, forming a resilient and self-healing network. The primary function is to broadcast small
messages to all nodes in the network.

The main user interface is a web-based dashboard that shows the status of the mesh network,
including the device ID, P2P status, and the number of peers. It also lists available
"visualizations" which are other HTML files in the `assets` folder.

The application is written in Kotlin and uses Gradle for building and Jetpack Compose for the UI.

## Building and Running

This is a standard Android project. It can be built and run using Android Studio or the `gradlew`
command-line tool.

To build the project from the command line, run:

```bash
./gradlew build
```

To install and run the application on a connected device or emulator, run:

```bash
./gradlew installDebug
./gradlew run
```

The main activity is `MainActivity.kt`, which handles permissions and then launches
`WebAppActivity.kt` to display the WebView.

## Development Conventions

The code is well-structured and includes comments. One-line comments are used to hint for any var or
fun that isn't immediately clear from the name. Block comments describe what makes each class "
special" and details the relationship to other classes. This is especially useful for speration of
concerns.

The `docs/snake.md` file provides a detailed explanation of the mesh topology plan. The project uses
a `libs.versions.toml` file to manage
dependencies.

The core logic for the mesh network is in
`app/src/main/java/info/benjaminhill/localmesh2/NearbyConnectionsManager.kt`. The main UI is a
WebView that displays content from the `assets` folder. The main dashboard is
`app/src/main/assets/index.html`.

## Development Guidelines

1. Strongly prefer simplicity. Straightforward code is best. Prefer concise code over future-proof
   code.
2. Prefer fluent syntax. Prefer early-return over deeply nested IF statements.
3. Strongly prefer modern syntax. If a Kotlin 2.x function exists to do something, use it over
   one-off code. This is espcially true for string parsing.
4. Assume that the devices running this app are very flaky: they run out of batteries, crash, or
   lock up at any time.
5. Always skip reading content of the "three.min.js" or "Tween.min.js"  when reading all files, they
   are too big to fit into GEMINI context. **Proactively read** the *.kt, *.html, and other *.js
   files often to stay up to date.
6. Logcat is always too big to read into GEMINI context. It must always be read using filters, and
   the filters can be gradually relaxed as needed.
7. Be pessimistic! **Never** assume "this fix is sure to work" or "My theory why this thing isn't
   working is enough to start coding my way out of it" - **always** get confirmation before writing
   code that you are on the right path. You will be fined $100 every time you forget this rule.
8. Be skeptical! **NOTHING** I suggest should ever make you say "That's an excellent idea!". It
   should always be "Here are the pros, here are the cons" balanced consideration. You have more
   context and
   recent Android app best practice knowledge than I do.
9. Don't bang your head against the wall: If you have a failure more than 3 times in a row (e.g.
   trying to resolve a dependency), HALT and ask the user for help. Somtimes an IDE can easily fix
   issues that the gemini-cli can't. Other examples
10. Magic numbers are a bad code smell (e.g. `val uid = String(bytes.copyOfRange(0, 36))`) and a good indication that a data class could help.

## Documented Development

Always tackle large feature requests using the following steps:

1. Write down the goals, the features, the design, and the list of changes in a
   {FEATURE_NAME}.md file
2. As the feature is implemented, mark the features in the md file complete.
3. No feature is complete until the code is compiled, lint-checked, and auto-formatted.
4. Not every feature needs unit-testing: The emulator can't form a P2P network (no bluetooth)
   and can't run all the visualizations (no motion detector or sound detector). Such features need a
   note in the code that they require manual testing.
5. Some bugs may take multiple attempts to fix. Write down **ALL** bugs in BUGS.md. Each bug should
   have "Severity", "State" (open, closed, wont_fix), "Description", "Location in Code", "Attempts".