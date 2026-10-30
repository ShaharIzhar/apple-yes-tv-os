# Installing the Yes Launcher with Claude Code

Instructions for Claude Code. The person running you wants the Yes Launcher on
their yes streamer box (or another Android TV box). They may never have used a
terminal or developer mode, so guide them one step at a time, in plain words,
and wait for their answer after each step before going on. The full reference
is `launcher/README.md` (Option 2); this file is the order to do it in.

## 1. Set up the TV, step by step

Explain these steps to the person one at a time, and ask them to tell you when
each one is done (or what they see instead):

1. On the TV, open **Settings → Device Preferences → About**, scroll down to
   **Build**, and press **Select** on it **7 times**, until the TV says they are
   now a developer.
2. Press **Back** once to **Device Preferences**, open **Developer options**, and
   turn on **USB debugging**. If they also see *Network debugging* or *ADB
   debugging*, turn that on too.
3. Open **Settings → Network & Internet**, select the network the TV is on, and
   read out the **IP address** (it looks like `192.168.1.50`).

## 2. Ask for the IP address

Ask: "What IP address does the TV show?" Wait for the answer. Check it looks
like four numbers separated by dots, and that this computer can reach it:
`nc -z -G 3 <ip> 5555` on a Mac (`nc -z -w 3 <ip> 5555` on Linux). If the port
is closed, USB debugging is probably still off, or the computer and TV are on
different networks: help them check, don't scan their network.

## 3. Check this computer

Check for `git`, `adb`, Java 17 and the Android SDK (platform 34). For anything
missing, explain what it is for and **ask before installing it** (README "What
you need"). Make sure `JAVA_HOME` points at Java 17 and the build can find the
SDK (`ANDROID_HOME` or `launcher/local.properties`).

## 4. API keys (optional)

Ask whether they want show art from TMDB and fanart.tv. If yes, explain how to
get the two free keys (README "What you need") and how to set
`TMDB_API_KEY` / `FANART_API_KEY` in their shell. Tell them **not** to paste the
keys into this chat, and never print or write the keys anywhere yourself. If
they don't want them, carry on: the launcher works without.

## 5. Build and install

Clone the repo if needed and run `launcher/tools/install.sh <ip>`. Before it
connects, tell them: **"Look at the TV now: when it asks 'Allow USB
debugging?', tick 'Always allow from this computer' and press OK."** The
script waits for that. It should end with
`Installed; Home-button helper is bound.` If it fails, use the README's
Troubleshooting table.

## 6. Check, and finish

1. Ask them to open any app and then press **Home on the real remote**, and to
   tell you where it lands. It should be the new home screen. (Home sent over
   `adb` skips the helper, so only the real remote proves it.)
2. Offer the optional memory step (README Option 2, step 5:
   `launcher/tools/stock-home.sh <ip> off`), and explain it is reversible.
3. Remind them to turn **USB debugging off** again in Developer options; the
   launcher keeps working.
4. Tell them how to update later (README "Updating later").

## Rules

- Never run `adb shell am force-stop tv.yes.launcher`: Android then switches
  the Home-button helper off. If that happens, run `install.sh` again.
- Don't disable, suspend or uninstall anything on the TV beyond what the README
  describes, and ask before any optional step.
- Don't commit, push or open pull requests to this repository.
