package com.sparrowwallet.drongo.wallet;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.Script;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.protocol.SigHash;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.protocol.TransactionInput;
import com.sparrowwallet.drongo.protocol.TransactionSignature;
import com.sparrowwallet.drongo.protocol.TransactionOutput;
import com.sparrowwallet.drongo.protocol.TransactionWitness;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates per-coin parity vectors for the sparrow-ltc-mobile port: chain parameters, keys, addresses and fully
 * signed two-input transactions for every coin, network and single-sig script type. RFC6979 makes the signatures
 * deterministic, so the mobile implementation must reproduce every byte.
 *
 * Run:    from a drongo checkout, COIN_VECTORS_OUT=<mobile>/vectors/coins.json ./gradlew test --tests '*CoinVectorGenerator*'
 * Output: COIN_VECTORS_OUT, default ../../mobile/vectors/coins.json; and TAPROOT_PSBT_OUT, default
 *         ../../mobile/vectors/taproot-psbt.json
 */
public class CoinVectorGeneratorTest {
    private static final String MNEMONIC = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
    private static final List<ScriptType> SCRIPT_TYPES = List.of(ScriptType.P2PKH, ScriptType.P2SH_P2WPKH, ScriptType.P2WPKH, ScriptType.P2TR);
    private static final String PREV_TXID_0 = "11".repeat(32);
    private static final String PREV_TXID_1 = "22".repeat(32);
    private static final long[] INPUT_VALUES = {200000000L, 300000000L};
    private static final long SEQUENCE = 0xFFFFFFFDL;

    @Test
    public void generate() throws Exception {
        DeterministicSeed seed = new DeterministicSeed(MNEMONIC, "", 0, DeterministicSeed.Type.BIP39);
        List<String> entries = new ArrayList<>();
        List<String> signedTxs = new ArrayList<>();
        Network previousNetwork = Network.get();

        try {
            for(Coin coin : Coin.values()) {
                for(Network network : List.of(Network.MAINNET, Network.TESTNET)) {
                    if(!coin.supports(network)) {
                        continue;
                    }

                    Coin previousCoin = Coin.beginScope(coin);
                    Network.set(network);
                    try {
                        List<String> scriptTypes = new ArrayList<>();
                        for(ScriptType scriptType : SCRIPT_TYPES) {
                            if(!coin.supports(scriptType)) {
                                continue;
                            }
                            Keystore keystore = Keystore.fromSeed(seed, PolicyType.SINGLE_HD, scriptType.getDefaultDerivation());
                            Wallet wallet = wallet(keystore, scriptType);
                            scriptTypes.add(scriptTypeJson(keystore, wallet, scriptType));
                            signedTxs.add(signedTxJson(coin, network, keystore, wallet, scriptType));
                        }
                        entries.add("    {\"coin\": " + q(coin.name()) + ", \"network\": " + q(network.name()) + ",\n"
                                + "      \"params\": " + paramsJson(coin, network) + ",\n"
                                + "      \"scriptTypes\": [\n" + String.join(",\n", scriptTypes) + "\n      ]\n    }");
                    } finally {
                        Coin.endScope(previousCoin);
                    }
                }
            }
        } finally {
            Network.set(previousNetwork);
        }

        String json = "{\n"
                + "  \"description\": \"Per-coin parity vectors from drongo (Sparrow-Scrypt desktop). Mobile core-crypto must match byte-for-byte.\",\n"
                + "  \"mnemonic\": " + q(MNEMONIC) + ",\n"
                + "  \"entries\": [\n" + String.join(",\n", entries) + "\n  ],\n"
                + "  \"signedTxs\": [\n" + String.join(",\n", signedTxs) + "\n  ]\n"
                + "}\n";

        String outEnv = System.getenv("COIN_VECTORS_OUT");
        Path out = Path.of(outEnv != null ? outEnv : "../../mobile/vectors/coins.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, json);
        System.out.println("Coin vectors written to " + out.toAbsolutePath().normalize());

        String taprootEnv = System.getenv("TAPROOT_PSBT_OUT");
        Path taprootOut = Path.of(taprootEnv != null ? taprootEnv : "../../mobile/vectors/taproot-psbt.json");
        Files.createDirectories(taprootOut.getParent());
        Files.writeString(taprootOut, taprootPsbtJson(seed));
        System.out.println("Taproot PSBT vector written to " + taprootOut.toAbsolutePath().normalize());
    }

    /**
     * A Litecoin testnet Taproot wallet spending two coins to an external address with change, through drongo's own
     * PSBT path: the unsigned PSBT the desktop exports (v0, outputs in order), the PSBT it signs, and the final tx.
     */
    private static String taprootPsbtJson(DeterministicSeed seed) throws Exception {
        Network previousNetwork = Network.get();
        Coin previousCoin = Coin.beginScope(Coin.LITECOIN);
        Network.set(Network.TESTNET);
        try {
            Keystore keystore = Keystore.fromSeed(seed, PolicyType.SINGLE_HD, ScriptType.P2TR.getDefaultDerivation());
            Wallet wallet = wallet(keystore, ScriptType.P2TR);
            wallet.setStoredBlockHeight(800006);
            WalletNode receiveNode = wallet.getNode(KeyPurpose.RECEIVE);
            receiveNode.fillToIndex(wallet, 1);
            WalletNode changeNode = wallet.getNode(KeyPurpose.CHANGE);
            changeNode.fillToIndex(wallet, 0);
            WalletNode[] spent = new WalletNode[2];
            for(WalletNode child : receiveNode.getChildren()) {
                if(child.getIndex() < 2) {
                    spent[child.getIndex()] = child;
                }
            }
            WalletNode change0 = changeNode.getChildren().stream().filter(n -> n.getIndex() == 0).findFirst().orElseThrow();
            Address external = ScriptType.P2WPKH.getAddress(PolicyType.SINGLE_HD, ECKey.fromPrivate(Sha256Hash.hash("external payee".getBytes(java.nio.charset.StandardCharsets.UTF_8))));

            //Coin 0 is output 0 of one funding tx, coin 1 output 1 of another
            Transaction fundingA = new Transaction();
            fundingA.addInput(Sha256Hash.ZERO_HASH, 0, new Script(new byte[0]));
            fundingA.addOutput(INPUT_VALUES[0], wallet.getAddress(spent[0]));
            Transaction fundingB = new Transaction();
            fundingB.addInput(Sha256Hash.ZERO_HASH, 1, new Script(new byte[0]));
            fundingB.addOutput(1000L, external);
            fundingB.addOutput(INPUT_VALUES[1], wallet.getAddress(spent[1]));
            Transaction[] fundings = {fundingA, fundingB};
            java.util.Date date = new java.util.Date(0);
            java.util.Map<Sha256Hash, BlockTransaction> blockTransactions = new java.util.LinkedHashMap<>();
            java.util.Map<BlockTransactionHashIndex, WalletNode> selected = new java.util.LinkedHashMap<>();
            for(int i = 0; i < 2; i++) {
                blockTransactions.put(fundings[i].getTxId(), new BlockTransaction(fundings[i].getTxId(), 800000, date, 0L, fundings[i]));
                BlockTransactionHashIndex utxo = new BlockTransactionHashIndex(fundings[i].getTxId(), 800000, date, 0L, i, INPUT_VALUES[i]);
                spent[i].getTransactionOutputs().add(utxo);
                selected.put(utxo, spent[i]);
            }
            wallet.updateTransactions(blockTransactions);

            long amount = 450000000L;
            long changeValue = 40000000L;
            long fee = INPUT_VALUES[0] + INPUT_VALUES[1] - amount - changeValue;
            Transaction tx = new Transaction();
            tx.setVersion(2);
            tx.setLocktime(0);
            for(int i = 0; i < 2; i++) {
                TransactionInput input = tx.addInput(fundings[i].getTxId(), i, new Script(new byte[0]), new TransactionWitness(tx));
                input.setSequenceNumber(SEQUENCE);
            }
            TransactionOutput paymentOut = tx.addOutput(amount, external);
            TransactionOutput changeOut = tx.addOutput(changeValue, wallet.getAddress(change0));
            Payment payment = new Payment(external, "Payment", amount, false);
            List<WalletTransaction.Output> outputs = List.of(new WalletTransaction.PaymentOutput(paymentOut, payment),
                    new WalletTransaction.ChangeOutput(changeOut, change0, changeValue));
            WalletTransaction walletTransaction = new WalletTransaction(wallet, tx, List.of(), List.of(selected), List.of(payment), outputs,
                    java.util.Map.of(change0, changeValue), fee);

            //PSBTv0 with global xpubs and the outputs kept in order, as the phone reads it
            PSBT psbt = new PSBT(walletTransaction, 0, true, false);
            String unsigned = java.util.Base64.getEncoder().encodeToString(psbt.serialize());
            wallet.sign(psbt);
            //Serialized before finalising, which clears the derivations
            String signed = java.util.Base64.getEncoder().encodeToString(psbt.serialize());
            wallet.finalise(psbt);
            String txHex = Utils.bytesToHex(psbt.extractTransaction().bitcoinSerialize());

            return "{\n"
                    + "  \"description\": \"A Litecoin testnet Taproot (BIP86) PSBT made, signed and finalized by drongo (Sparrow-Scrypt desktop). The phone must sign it to the same transaction.\",\n"
                    + "  \"mnemonic\": " + q(MNEMONIC) + ",\n"
                    + "  \"coin\": \"LITECOIN\", \"network\": \"TESTNET\", \"scriptType\": \"P2TR\",\n"
                    + "  \"derivationPath\": " + q(keystore.getKeyDerivation().getDerivationPath()) + ",\n"
                    + "  \"masterFingerprint\": " + q(keystore.getKeyDerivation().getMasterFingerprint()) + ",\n"
                    + "  \"inputValues\": [" + INPUT_VALUES[0] + ", " + INPUT_VALUES[1] + "],\n"
                    + "  \"externalAddress\": " + q(external.toString()) + ", \"amount\": " + amount + ", \"change\": " + changeValue + ", \"fee\": " + fee + ",\n"
                    + "  \"unsignedPsbt\": " + q(unsigned) + ",\n"
                    + "  \"signedPsbt\": " + q(signed) + ",\n"
                    + "  \"txHex\": " + q(txHex) + "\n"
                    + "}\n";
        } finally {
            Coin.endScope(previousCoin);
            Network.set(previousNetwork);
        }
    }

    private static Wallet wallet(Keystore keystore, ScriptType scriptType) {
        Wallet wallet = new Wallet();
        wallet.setPolicyType(PolicyType.SINGLE_HD);
        wallet.setScriptType(scriptType);
        wallet.getKeystores().add(keystore);
        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE_HD, scriptType, wallet.getKeystores(), 1));
        return wallet;
    }

    private static String paramsJson(Coin coin, Network network) {
        return "{\"p2pkhVersion\": " + network.getP2PKHAddressHeader()
                + ", \"p2shVersion\": " + network.getP2SHAddressHeader()
                + ", \"wifVersion\": " + network.getDumpedPrivateKeyHeader()
                + ", \"xpubHeader\": " + q(String.format("%08x", network.getXpubHeader().getHeader()))
                + ", \"xprvHeader\": " + q(String.format("%08x", network.getXprvHeader().getHeader()))
                + ", \"bech32Hrp\": " + q(network.getBech32AddressHRP())
                + ", \"mwebHrp\": " + q(coin.supports(ScriptType.MWEB) ? network.getMwebAddressHrp() : null)
                + ", \"coinType\": " + coin.getBip44CoinType()
                + ", \"recommendedFeeRate\": " + coin.getRecommendedFeeRate()
                + ", \"dustLimit\": " + coin.getDustLimit()
                + ", \"segwit\": " + coin.isSegwitSupported()
                + ", \"mweb\": " + coin.supports(ScriptType.MWEB)
                + ", \"auxPow\": " + coin.isAuxPow()
                + ", \"ticker\": " + q(coin.getTicker())
                + ", \"subunit\": " + q(coin.getSubunit())
                + ", \"coingeckoId\": " + q(coin.getCoingeckoId())
                + ", \"uriScheme\": " + q(coin.getUriScheme()) + "}";
    }

    private static String scriptTypeJson(Keystore keystore, Wallet wallet, ScriptType scriptType) throws Exception {
        WalletNode receive0 = new WalletNode(wallet, KeyPurpose.RECEIVE, 0);
        List<String> receive = new ArrayList<>();
        List<String> change = new ArrayList<>();
        for(int i = 0; i < 2; i++) {
            receive.add(q(new WalletNode(wallet, KeyPurpose.RECEIVE, i).getAddress().toString()));
            change.add(q(new WalletNode(wallet, KeyPurpose.CHANGE, i).getAddress().toString()));
        }
        return "        {\"scriptType\": " + q(scriptType.name())
                + ", \"derivationPath\": " + q(keystore.getKeyDerivation().getDerivationPath())
                + ", \"accountXpub\": " + q(keystore.getExtendedPublicKey().toString())
                + ", \"receive0Wif\": " + q(keystore.getKey(receive0).getPrivateKeyEncoded().toString())
                + ",\n         \"receiveAddresses\": [" + String.join(", ", receive) + "]"
                + ", \"changeAddresses\": [" + String.join(", ", change) + "]}";
    }

    private static String signedTxJson(Coin coin, Network network, Keystore keystore, Wallet wallet, ScriptType scriptType) throws Exception {
        WalletNode[] spent = {new WalletNode(wallet, KeyPurpose.RECEIVE, 0), new WalletNode(wallet, KeyPurpose.RECEIVE, 1)};
        String[] prevTxids = {PREV_TXID_0, PREV_TXID_1};
        boolean segwit = scriptType != ScriptType.P2PKH;

        Transaction tx = new Transaction();
        tx.setVersion(2);
        tx.setLocktime(0);
        for(int i = 0; i < 2; i++) {
            TransactionInput input = segwit
                    ? tx.addInput(Sha256Hash.wrap(prevTxids[i]), i, new Script(new byte[0]), new TransactionWitness(tx))
                    : tx.addInput(Sha256Hash.wrap(prevTxids[i]), i, new Script(new byte[0]));
            input.setSequenceNumber(SEQUENCE);
        }
        tx.addOutput(450000000L, new WalletNode(wallet, KeyPurpose.RECEIVE, 2).getAddress());
        tx.addOutput(40000000L, new WalletNode(wallet, KeyPurpose.CHANGE, 0).getAddress());

        String[] spentScripts = new String[2];
        if(scriptType == ScriptType.P2TR) {
            //Taproot signs through drongo's own wallet and PSBT path, so the bytes are exactly what the desktop produces
            wallet.getNode(KeyPurpose.RECEIVE).fillToIndex(wallet, 2);
            PSBT psbt = new PSBT(tx);
            for(int i = 0; i < 2; i++) {
                Script spentScript = spent[i].getOutputScript();
                spentScripts[i] = Utils.bytesToHex(spentScript.getProgram());
                psbt.getPsbtInputs().get(i).setWitnessUtxo(new TransactionOutput(null, INPUT_VALUES[i], spentScript));
            }
            wallet.sign(psbt);
            wallet.finalise(psbt);
            tx = psbt.extractTransaction();
        } else {
            //Every legacy digest is taken before any scriptSig is set: a legacy sighash covers the other inputs' scripts
            ECKey[] keys = new ECKey[2];
            TransactionSignature[] sigs = new TransactionSignature[2];
            for(int i = 0; i < 2; i++) {
                keys[i] = keystore.getKey(spent[i]);
                Script p2pkh = ScriptType.P2PKH.getOutputScript(keys[i].getPubKeyHash());
                Sha256Hash sighash = segwit
                        ? tx.hashForWitnessSignature(i, p2pkh, INPUT_VALUES[i], SigHash.ALL)
                        : tx.hashForLegacySignature(i, p2pkh, SigHash.ALL);
                sigs[i] = new TransactionSignature(keys[i].signEcdsa(sighash), SigHash.ALL);
            }
            for(int i = 0; i < 2; i++) {
                TransactionInput input = tx.getInputs().get(i);
                Script outputScript = scriptType.getOutputScript(PolicyType.SINGLE_HD, keys[i]);
                if(scriptType != ScriptType.P2WPKH) {
                    input.setScriptBytes(scriptType.getScriptSig(PolicyType.SINGLE_HD, outputScript, keys[i], sigs[i]).getProgram());
                }
                if(segwit) {
                    input.setWitness(new TransactionWitness(tx, keys[i], sigs[i]));
                }
            }
            for(int i = 0; i < 2; i++) {
                spentScripts[i] = Utils.bytesToHex(scriptType.getOutputScript(PolicyType.SINGLE_HD, keys[i]).getProgram());
            }
        }

        String path = keystore.getKeyDerivation().getDerivationPath();
        List<String> inputs = new ArrayList<>();
        for(int i = 0; i < 2; i++) {
            inputs.add("{\"prevTxid\": " + q(prevTxids[i]) + ", \"vout\": " + i + ", \"value\": " + INPUT_VALUES[i]
                    + ", \"keyPath\": " + q(path + "/0/" + i) + ", \"sequence\": " + SEQUENCE
                    + ", \"spentScript\": " + q(spentScripts[i]) + "}");
        }
        List<String> outputs = new ArrayList<>();
        for(var out : tx.getOutputs()) {
            outputs.add("{\"value\": " + out.getValue() + ", \"script\": " + q(Utils.bytesToHex(out.getScript().getProgram())) + "}");
        }
        return "    {\"coin\": " + q(coin.name()) + ", \"network\": " + q(network.name()) + ", \"scriptType\": " + q(scriptType.name())
                + ",\n      \"inputs\": [" + String.join(", ", inputs) + "]"
                + ",\n      \"outputs\": [" + String.join(", ", outputs) + "]"
                + ",\n      \"txHex\": " + q(Utils.bytesToHex(tx.bitcoinSerialize())) + "}";
    }

    private static String q(String s) {
        return s == null ? "null" : "\"" + s + "\"";
    }
}
