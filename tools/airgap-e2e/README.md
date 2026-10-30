# Air-gapped mode, end-to-end on the emulator

Checks the phone side of air-gapped signing without a camera: the wallet export is read
from screenshots, and PSBT frames go in through a debug-only adb hook.

Use the `sparrow_test` AVD and throwaway testnet wallets only.

## Tools

`UrTool.java` runs as a single-file Java program. Its classpath is Hummingbird 1.7.6,
cbor 0.9, and zxing core and javase 3.5.4, all from the Gradle cache:

```
java -cp <hummingbird>;<cbor>;<zxing-core>;<zxing-javase> tools/airgap-e2e/UrTool.java selftest
java ... UrTool.java decode [--testnet] f1.png f2.png ...   # exit 2 while incomplete
java ... UrTool.java encode <base64-psbt>                    # ur:crypto-psbt parts, one per line
```

`AirgapE2eTest` in `:core-crypto` writes the PSBT the phone signs and checks the result.
It does nothing unless `AIRGAP_E2E_DIR` is set:

```
AIRGAP_E2E_DIR=<dir> ./gradlew :core-crypto:jvmTest --tests '*AirgapE2e*' --rerun
```

The first run writes `<dir>/unsigned.psbt`. It is a desktop-style PSBT, with the full
previous transaction and proprietary pairs, for the `abandon ... about` seed on testnet.

If `<dir>/signed.psbt` exists, the run also checks it:
- only partial signatures were added;
- it finalizes to exactly the transaction that direct signing produces.

When both checks pass, it writes `verified.txt`.

## Run

1. Install the debug APK. Restore `abandon ... about` as a **Testnet** wallet, then turn
   on air-gapped mode.
2. **Export.** On Home, open Export to desktop. Capture frames with
   `adb exec-out screencap -p > fN.png` (about 30), then run
   `UrTool decode --testnet f*.png`. The descriptor's tpub must equal the xpub on the
   phone's Details screen.
3. **Feed the PSBT.** Open Sign a transaction, then Scan QR. Send each line of
   `UrTool encode "$(cat unsigned.psbt)"` like this:

   ```
   adb shell am broadcast -n com.sparrowwallet.mobile/.DebugUrReceiver \
       -a com.sparrowwallet.mobile.DEBUG_UR --es part <part> </dev/null
   ```

   The `</dev/null` matters inside a `while read` loop, because adb would otherwise eat
   the loop's input.
4. **Sign.** Check the review: outputs, change and fee. Sign, capture about 40 frames,
   and save the output of `UrTool decode f*.png` as `signed.psbt`. Re-run `AirgapE2eTest`.
5. **Network check.** Throughout, the app's uid should show no traffic.
   `/proc/net` is closed to shell and `run-as` on this image, so read the per-uid bytes
   from `adb shell dumpsys netstats detail` after `dumpsys netstats --poll`.
6. **Go online.** Turn the mode off with every wallet's password.

A funded run, with the desktop importing the export by webcam and broadcasting, needs
desktop Sparrow and testnet coins. It is not covered here.
