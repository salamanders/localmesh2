<!-- ![Local Mesh Icon](https://raw.githubusercontent.com/salamanders/localmesh2/main/app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp) -->

# LocalMesh
A simple local mesh network for rapid prototyping

**LocalMesh** creates a private, peer-to-peer network with nearby devices, allowing you to chat,
share files, and run web applications without any internet or cellular connection. It turns a group
of phones into their own self-contained network.

## Key Features

* **🔌 Serverless & Offline:** No internet, no problem. LocalMesh discovers and connects devices
  automatically.
* **💬 Chat & File Sharing:** Easily send messages and share files directly with anyone on your local
  network.
* **🌐 Web-Based Interface:** A clean, simple web UI for all interactions, accessible from your
  phone's browser.
* **🛠️ Extensible for Developers:** Build your own offline, multi-device web apps on top of the
  LocalMesh platform using simple HTTP requests.

## Use Cases

* **🏕️ Outdoor & Off-the-Grid:** Stay connected while hiking, camping, or traveling in remote areas.
* **🎉 Crowded Events:** Communicate reliably at concerts, festivals, and stadiums where cell
  networks fail.
* **📚 Collaborative Environments:** Create local networks for classrooms, workshops, or team
  projects.
* **🚨 Emergency Preparedness:** A resilient communication tool when primary infrastructure is
  unavailable.

## Getting Started

1. **Install the App:** Install LocalMesh on two or more nearby Android phones.
2. **Launch & Connect:**
    * Open the app on each phone.
    * Tap the **"Start Service"** button.
    * The app will automatically find and connect to other LocalMesh users.
3. **Open the Web UI:** The app will launch a web page where you can see connected peers, chat, and
   share.

## For Developers

This project is built on a simple idea: treating the P2P network as a transparent
transport layer for standard HTTP requests. Any feature you can build as a web endpoint is
automatically available to peers.

To dive into the architecture, data flows, and build instructions, please see our
comprehensive [Developer Context Guide (GEMINI.md)](GEMINI.md).

## Contributing

Contributions are welcome! Whether it's bug reports, feature suggestions, or code improvements,
please feel free to open an issue or submit a pull request.

## License

This project is licensed under the [MIT License](LICENSE).
