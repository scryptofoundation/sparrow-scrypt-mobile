# Security Policy

## Reporting a Vulnerability

Please do not report security vulnerabilities through public GitHub issues.

Instead, report them privately using the Security tab of this repository (Report a vulnerability). This creates a private security advisory visible only to you and the maintainers.

## What to include

To help triage the report quickly, please include as much of the following as you can:

- The Sparrow-Scrypt Mobile version, the coin, the network (mainnet or testnet), your phone and Android version, and how you got the app (release APK or built from source)
- A description of the vulnerability and its impact
- Steps to reproduce, ideally with a proof of concept
- Any relevant configuration (wallet type, script type, connected server, Tor, MWEB, air-gapped mode, a wallet linked from the desktop)

Please do not include seed words, private keys or wallet passwords in a report. If a wallet is needed to reproduce the issue, create one on a test network or with no real funds.

## What to expect

- An acknowledgement of your report, usually within a few days
- An assessment of the issue
- Updates on the advisory as the fix progresses

Fixes for serious issues are released as soon as they are ready, rather than being held for a scheduled release.

## Disclosure

Reports are assigned one of four severity levels, which determines when details are made public:

| Severity | Description | Disclosure |
| --- | --- | --- |
| Low | Hard to exploit, with minor impact. Typically requires a non-default configuration or local access to the victim's machine. | No advisory, disclosed in the release notes |
| Medium | Limited in scope, or requiring special conditions to trigger. | 1 year after a release containing the fix |
| High | Significant impact, and typically exploitable under a default configuration. | 1 year after a release containing the fix |
| Critical | Threatens user funds at scale, such as remote theft requiring no user interaction. | Ad hoc |

The delay before disclosing Medium and High severity issues gives users of older versions time to upgrade. Critical issues are handled case by case.

Severity is assigned by the maintainers. Reports that do not show what an attacker gains, and what access they need to gain it, are handled as ordinary bugs through the public issue tracker. These are not assigned a severity and no advisory is published.

Credit is given to the reporter in the advisory or the release notes, unless you prefer to stay anonymous. Please give us the chance to release a fix before you disclose an issue publicly, and say so in your report if you plan to disclose on a timeline of your own.

There is currently no bug bounty programme for Sparrow-Scrypt Mobile.

## Supported Versions

Sparrow-Scrypt Mobile is in beta. Security fixes are made against the latest release only, available from the [Releases](../../releases) page.

Every release APK is signed with the same key, whose certificate fingerprint is in the README, and each release lists a SHA256 checksum: check a download against it before you install it.

## Scope

This policy covers the Android app in this repository. Issues in the desktop app, [Sparrow-Scrypt](https://github.com/scryptofoundation/sparrow-scrypt), go to that repository; if an issue spans both, for example in the QR link between them, report it in either and say so.

The app follows the desktop app's wallet formats, which come from Sparrow-LTC and Sparrow Wallet. If an issue also affects those projects, say so in your report; it may need to be reported to them as well.

Issues we are particularly interested in:

- Loss or theft of funds, including anything that sends a transaction to an address other than the one displayed
- Signing or broadcasting a transaction on the wrong coin or network, or showing one coin's address in another coin's format
- Disclosure of private keys, seed words, wallet passwords, MWEB scan or spend keys, or the contents of an encrypted seed backup
- Code execution or a crash an attacker controls, including via QR codes, PSBTs, links from the desktop, URI handling, or a malicious or compromised server or inscription indexer
- Bypasses of the wallet vault's encryption or the unlock prompt, or keys left readable on the phone
- Spending an output that holds an inscription or rune without the warning and release the app requires
- Air-gapped mode reaching the network, or signing a PSBT for another coin or with outputs other than those shown
- Privacy leaks that link wallet addresses to a user's identity or network address beyond what is inherent to the connection type chosen

Generally out of scope:

- Vulnerabilities in third-party servers, block explorers, inscription indexers, Android itself, Orbot or upstream dependencies, which should be reported to the relevant project (though we appreciate being told where the app's use of them makes the impact worse)
- Attacks requiring a rooted or already compromised phone, or physical access to an unlocked phone
- Missing hardening or best-practice recommendations without a demonstrated impact
- Reports generated by automated scanners or language models where the reporter has not independently verified the issue and demonstrated its impact
