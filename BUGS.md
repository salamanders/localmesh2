# Bugs

---
Bug Template

* Severity: (High, Medium, Low)
* State: (Open, Fixed, Won't-Fix)
* Description: (One to three line description, including current vs desired behavior)
* Location in Code
    * (Link to file(s))
* Attempts: (What did you try to prove that this bug exists, prove that you located the right cause,
  attempts to fix, attempts to show that the fix worked as expected)
    * (Date): (Description)
    * (Date): (Description)

---

* Severity: High
* State: Open
* Description: When I click a visualization on the commander, neither the Lieutenant or Client react.
* Location in Code
    * app/src/main/java/info/benjaminhill/localmesh2/p2p/AbstractConnection.kt
* Attempts:
    * (2025-10-30): Hypothesis: The commander is sending the message, but the other devices are not receiving it.
        * Evidence:
            * Logcat on the commander shows the message being sent.
            * Logcat on the lieutenant and client are empty.
    * (2025-10-30): Hypothesis: The commander thinks it has no downstream endpoints, so it's not even trying to send the message.
        * Evidence:
            * Logcat on the commander shows "No downstream endpoints to broadcast to."
            * Logcat on the commander shows "Advertising: onConnectionResult failure for VBQS. Code: 13"

---

* Severity: High
* State: Open
* Description: The application does not reflect the connection status of other devices in the UI. The logs show that connections are being established, but the UI does not update.
* Location in Code
    * app/src/main/java/info/benjaminhill/localmesh2/p2p/AbstractConnection.kt
    * app/src/main/java/info/benjaminhill/localmesh2/WebAppActivity.kt
    * app/src/main/java/info/benjaminhill/localmesh2/JavaScriptInjectedAndroid.kt
* Attempts:
    * (2025-10-30): Hypothesis: The UI is not being updated because there is no mechanism to observe the changes in the `ourClients` and `ourDiscoveredControllers` sets in `AbstractConnection.kt`.
        * Evidence:
            * Logcat analysis shows that connections are being established successfully at the Nearby Connections API level.
            * Code review of `WebAppActivity.kt` and `JavaScriptInjectedAndroid.kt` confirms that there is no code to observe the connection state from `AbstractConnection.kt`.

---

* Severity: High
* State: Fixed
* Description: I started one phone as "Commander" and 4 phones as "Lieutenant" but didn't see any
  indication that they connected.
  Location in Code:
    * app/src/main/java/info/benjaminhill/localmesh2/CommanderConnection.kt,
    * app/src/main/java/info/benjaminhill/localmesh2/LieutenantConnection.kt
* Attempts:
    * (2025-10-30): Jules's Hypothesis: The connection fails because the Lieutenant is incorrectly
      trying to accept its own connection request to the Commander. This was proven to be incorrect.
        * **Initial (Incorrect) Flow:**
            1. Commander starts advertising.
            2. Lieutenant discovers the Commander.
            3. Lieutenant calls `requestConnection()` to connect.
            4. `onConnectionInitiated` is triggered on both devices.
            5. Commander correctly calls `acceptConnection()`.
            6. Lieutenant **incorrectly** also calls `acceptConnection()`. An endpoint cannot accept
               its own connection request. This likely causes the connection to hang.
        * **Correct Flow:**
            1. Commander starts advertising.
            2. Lieutenant discovers the Commander.
            3. Lieutenant calls `requestConnection()` to connect.
            4. `onConnectionInitiated` is triggered on both devices.
            5. Both Commander and Lieutenant call `acceptConnection()`.
    * (2025-10-30): The hypothesis that the Lieutenant should not call `acceptConnection` was
      incorrect. Both sides of the connection must call `acceptConnection`. The code was modified to
      call `acceptConnection` in the `lieutenantToCommanderConnectionLifecycleCallback` and provide
      the `payloadFromCommanderCallback`. This allows the Lieutenant to receive messages from the
      Commander. The build was successful after the change.