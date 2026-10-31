# sparrow-ltc-mobile

Kotlin Multiplatform mobile wallet for Litecoin (incl. MWEB), designed for byte-for-byte
parity with the Sparrow-LTC desktop wallet. See [DESIGN.md](DESIGN.md) for the architecture.

## Layout

- `core-crypto/` — the drongo port (BIP39/32, LTC addresses, MWEB view keys). JVM target
  only for now; iOS/Android targets get enabled as toolchains come online.
- `vectors/golden-vectors.json` — parity ground truth, **generated** from desktop drongo.
  Never hand-edit.

## Regenerate golden vectors

```sh
cd ../sparrow
JAVA_HOME=~/jvm/jdk-25.0.2+10 ./gradlew :drongo:test --tests '*GoldenVectorGenerator*'
```

(Generator source: `sparrow/drongo/src/test/java/com/sparrowwallet/drongo/wallet/GoldenVectorGeneratorTest.java`)

## Regenerate per-coin vectors

`vectors/coins.json` holds chain parameters, keys, addresses and signed transactions for every
coin, network and script type. Copy `tools/vectorgen/CoinVectorGeneratorTest.java` into a drongo
checkout's `src/test/java/com/sparrowwallet/drongo/wallet/`, then from that drongo checkout:

```sh
COIN_VECTORS_OUT=<this repo>/vectors/coins.json JAVA_HOME=~/jvm/jdk-25.0.2+10 ./gradlew test --tests '*CoinVectorGenerator*'
```

Commit the new `vectors/coins.json`. Never hand-edit it.

## Run parity tests

```sh
JAVA_HOME=~/jvm/jdk-25.0.2+10 ./gradlew :core-crypto:jvmTest
```
