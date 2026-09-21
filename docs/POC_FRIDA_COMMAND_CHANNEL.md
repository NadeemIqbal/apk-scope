# Experimental Frida command channel

This is a branch-scoped APK-repack POC. It is not a general-purpose Frida service and does not
change APK Scope's supported product boundary.

## Scope

- The command console is available only in the Work Profile while the current sandbox session is
  active.
- The controller binds to the session's target package and to a random token generated inside the
  Work-profile APK Scope process after the APK is received.
- The token is never embedded in the repacked APK. The injected script requests it through the
  exported `com.nadeem.apkscope.frida.channel` provider, which returns a token only when the Binder
  caller UID owns the session's target package.
- The injected process supplies its own package name and PID from `/proc/self/cmdline` and Frida's
  `Process.id`; the controller has no package, PID, attach, or spawn selector.
- The receiver listens explicitly on `127.0.0.1`, accepts one target connection, rejects a hello
  whose package or token does not match the active session, and ignores events from another package.
- Commands are bounded to 256 KiB and are JSON-lines over the existing local channel.

## Supported command operations

### UI location

Open APK Scope inside the Work Profile, enter the active **Live Monitor**, select the **Frida**
traffic source, and tap **Open Frida Command Console**. This opens the dedicated **Frida Console**
screen. The screen is available while the receiver is listening; its **Run** button remains disabled
until the target is authenticated and the script has passed local verification.

The console includes quick buttons for target identity, loaded native modules, target-process
threads, and loaded Java classes. **Beautify** formats the editor, while **Verify** checks delimiter
balance and unterminated strings/comments and shows a suggested fix for each issue.

Each submitted script and its target result appears as a command/result card in the transcript above
the bottom composer. The transcript is scoped to the active target-bound Work session.

The controller sends only:

- `ping`, for target identity confirmation;
- `evaluate`, for JavaScript evaluated in the already-connected target process.

The injected script does not expose Frida CLI operations such as `attach`, `spawn`, or selecting a
different package/process. JavaScript evaluated in the target can use the Frida APIs available in
that process, so this remains an experimental capability that must be used only with authorized
test APKs.

## Failure behavior

If the current session has no stored Work-profile token, if the target is disconnected, or if the
hello does not match the active package/token, the console remains unavailable. Ending the Work
Profile session first closes the live socket and then clears the stored token.
