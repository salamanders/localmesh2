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

