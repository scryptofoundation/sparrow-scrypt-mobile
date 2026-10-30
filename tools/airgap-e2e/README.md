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

The first run writes `<dir>/unsigned.psbt`. It is a desktop-style PSBT, with both inputs' full
previous transactions and proprietary pairs, for the `abandon ... about` seed on testnet.

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

## Desktop leg on the Litecoin regtest

Run 2026-10-30 with Sparrow-Scrypt from the `feature/sparrow-mobile-importer` branch, on
the regtest in `testnet-infra` (node 19443, ElectrumX 50105) and a throwaway `--dir`.

1. **Import.** With a webcam: New Wallet, Native Segwit, Airgapped Hardware Wallet,
   Sparrow Mobile, Scan. Without one, write the frame texts of step 2 to a file (one per
   line) and run desktop's `SparrowMobileScanImportLiveTest` with
   `SPARROW_MOBILE_SCAN_FRAMES` and `SPARROW_MOBILE_SCAN_OUT=<dir>/regtest/wallets/<name>.mv.db`.
   It uses the scan dialog's own UR decoding and the import pane's keystore handling.
2. **Fund** the first address twice (`ltc-regtest.sh fund`, then `mine 1`) so a spend
   needs two inputs.
3. **Send** from desktop with both coins, Finalize, Show QR. Every input of a multi-input
   PSBT must carry `0x00` (the full previous transaction); the phone refuses it otherwise.
4. Feed desktop's frames to the phone as above, sign, decode the phone's QR to a file,
   then on desktop Load Transaction and Broadcast. Mine a block to confirm.
