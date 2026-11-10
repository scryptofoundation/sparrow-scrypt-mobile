package com.sparrowwallet.sparrow.wallet.collectibles;

import com.google.gson.*;
import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.ordinals.track.*;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.sparrow.net.ord.OrdClient;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.*;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Records drongo's inscription tracer on one private inscription test chain (testnet-infra/inscriptions, started one at a
 * time) for the phone's parity test (core-crypto RecordedChainParityTest). Every answer the chain source gave the tracer
 * is kept (transactions as the server's raw hex, null answers, and errors as {"error": message}), with the chain rules
 * drongo used and the result of every trace, so the phone can replay the same traces without a network.
 *
 * Coins: every inscription the chain's indexer lists, at its current location, and the location transaction's other
 * unspent outputs (as LiveTrackerParityCheck picks them). Each coin is traced twice, with budget 200 and no block walk,
 * and with budget 2,000 and the walk, each time by a fresh tracer with an empty KnownResults and a fresh TxCache.
 *
 * Run (kept in the mobile repo, copied into desktop/sparrow/src/test/java/com/sparrowwallet/sparrow/wallet/collectibles/
 * only while it runs): TRACKER_CHAIN=litecoin|dogecoin|pepecoin|junkcoin|bellscoin TRACKER_OUT=<mobile>/vectors/tracker
 * [TRACKER_TIP=<node height to wait for>] [TRACKER_ELECTRUM_PORT=<port>] ./gradlew --no-daemon :test --rerun --tests '*TrackerRecording*'
 */
@EnabledIfEnvironmentVariable(named = "TRACKER_CHAIN", matches = ".+")
public class TrackerRecordingTest {
    private static final Pattern LINK = Pattern.compile("/(?:inscription|shibescription|junkscription)/([0-9a-f]{64}i\\d+)");
    private static final long COIN_UNITS = 100_000_000L;
    private static final int[][] RUNS = {{InscriptionTracer.DEFAULT_BUDGET, 0}, {InscriptionTracer.DEEPER_BUDGET, 1}};

    /** Newline-delimited JSON-RPC to an ElectrumX server. */
    static final class Electrum implements Closeable {
        private final int port;
        private Socket socket;
        private BufferedReader in;
        private Writer out;
        private int id;

        Electrum(int port) throws IOException {
            this.port = port;
            connect();
        }

        private void connect() throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(60_000);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            send("server.version", "tracker-recording", "1.4");
        }

        JsonElement call(String method, Object... params) throws IOException {
            try {
                return send(method, params);
            } catch(java.net.SocketException | EOFException e) {
                socket.close();
                connect();
                return send(method, params);
            }
        }

        private JsonElement send(String method, Object... params) throws IOException {
            JsonObject request = new JsonObject();
            request.addProperty("id", ++id);
            request.addProperty("method", method);
            request.add("params", new Gson().toJsonTree(params));
            out.write(request + "\n");
            out.flush();
            while(true) {
                String line = in.readLine();
                if(line == null) {
                    throw new EOFException("server closed the connection");
                }
                JsonObject response = JsonParser.parseString(line).getAsJsonObject();
                if(!response.has("id") || response.get("id").isJsonNull() || response.get("id").getAsInt() != id) {
                    continue;
                }
                if(response.has("error") && !response.get("error").isJsonNull()) {
                    throw new IOException(method + ": " + response.get("error"));
                }
                return response.get("result");
            }
        }

        int tip() throws IOException {
            return call("blockchain.headers.subscribe").getAsJsonObject().get("height").getAsInt();
        }

        boolean unspent(Transaction tx, int vout) throws IOException {
            byte[] hash = Sha256Hash.hash(tx.getOutputs().get(vout).getScriptBytes());
            byte[] reversed = new byte[hash.length];
            for(int i = 0; i < hash.length; i++) {
                reversed[i] = hash[hash.length - 1 - i];
            }
            for(JsonElement utxo : call("blockchain.scripthash.listunspent", Utils.bytesToHex(reversed)).getAsJsonArray()) {
                JsonObject entry = utxo.getAsJsonObject();
                if(entry.get("tx_hash").getAsString().equals(tx.getTxId().toString()) && entry.get("tx_pos").getAsInt() == vout) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /**
     * The test chain's Electrum server as the tracer's chain source (as LiveTrackerParityCheck reaches it: verbose
     * transactions, heights from confirmations against a fixed tip, no block fee lists, block positions), keeping every
     * answer. A call asked again must get the same answer, or the recording would not replay.
     */
    static final class RecordingSource implements ChainSource {
        private final Electrum electrum;
        private final int tip;
        final Map<String, JsonElement> transactions = new TreeMap<>();
        final Map<String, JsonElement> blockFees = new TreeMap<>();
        final Map<String, JsonElement> txidAt = new TreeMap<>();
        final Map<String, JsonElement> blockHash = new TreeMap<>();
        /** Heights of the coinbases the tracer read, to check the recorded reward rule against drongo's. */
        final Set<Integer> coinbaseHeights = new TreeSet<>();

        RecordingSource(Electrum electrum, int tip) {
            this.electrum = electrum;
            this.tip = tip;
        }

        private static void remember(Map<String, JsonElement> calls, String key, JsonElement answer) {
            JsonElement before = calls.putIfAbsent(key, answer);
            if(before != null && !before.equals(answer)) {
                throw new AssertionError("The server answered " + key + " differently: " + before + " then " + answer);
            }
        }

        private static JsonObject error(IOException e) {
            JsonObject error = new JsonObject();
            error.addProperty("error", e.getMessage());
            return error;
        }

        @Override
        public ChainTx transaction(Sha256Hash txid) throws IOException {
            JsonObject verbose;
            try {
                verbose = electrum.call("blockchain.transaction.get", txid.toString(), true).getAsJsonObject();
            } catch(IOException e) {
                remember(transactions, txid.toString(), error(e));
                throw e;
            }
            String hex = verbose.get("hex").getAsString();
            Transaction tx = new Transaction(Utils.hexToBytes(hex));
            int confirmations = verbose.has("confirmations") ? verbose.get("confirmations").getAsInt() : 0;
            int height = confirmations > 0 ? tip - confirmations + 1 : 0;
            Sha256Hash block = height > 0 && verbose.has("blockhash") ? Sha256Hash.wrap(verbose.get("blockhash").getAsString()) : null;
            long time = height > 0 && verbose.has("blocktime") ? verbose.get("blocktime").getAsLong() : 0;
            JsonObject answer = new JsonObject();
            answer.addProperty("hex", hex);
            answer.addProperty("height", height);
            answer.addProperty("blockHash", block == null ? null : block.toString());
            answer.addProperty("blockTime", time);
            remember(transactions, txid.toString(), answer);
            if(tx.isCoinBase() && height > 0) {
                coinbaseHeights.add(height);
            }
            return new ChainTx(tx, height, block, time, true);
        }

        @Override
        public Optional<List<BlockFee>> blockFees(int height, Sha256Hash hash) {
            //An Electrum server cannot list a block's fees
            remember(blockFees, height + ":" + hash, JsonNull.INSTANCE);
            return Optional.empty();
        }

        @Override
        public Optional<Sha256Hash> txidAt(int height, int position) throws IOException {
            String key = height + ":" + position;
            try {
                String txid = electrum.call("blockchain.transaction.id_from_pos", height, position).getAsString();
                remember(txidAt, key, new JsonPrimitive(txid));
                return Optional.of(Sha256Hash.wrap(txid));
            } catch(IOException e) {
                if(e.getMessage() != null && e.getMessage().toLowerCase(Locale.ROOT).contains("no tx at position")) {
                    remember(txidAt, key, JsonNull.INSTANCE);
                    return Optional.empty();
                }
                remember(txidAt, key, error(e));
                throw e;
            }
        }

        @Override
        public Optional<Sha256Hash> blockHash(int height) {
            //Never asked while verbose answers carry the block hash; kept so the replay can tell
            remember(blockHash, String.valueOf(height), JsonNull.INSTANCE);
            return Optional.empty();
        }
    }

    private static String get(String url) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).header("Accept", "text/html").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    private static List<String> listedInscriptions(String ordUrl) throws Exception {
        Set<String> ids = new LinkedHashSet<>();
        for(String page : List.of("/", "/inscriptions", "/shibescriptions")) {
            try {
                Matcher matcher = LINK.matcher(get(ordUrl + page));
                while(matcher.find()) {
                    ids.add(matcher.group(1));
                }
            } catch(Exception e) {
                //Not every indexer has every page
            }
        }
        return new ArrayList<>(ids);
    }

    /** The reward rule drongo's ChainRules codes for the chain, checked at every coinbase height the traces read (and one either side). */
    private static JsonObject subsidyRule(Coin coin, Network network, Set<Integer> heights) {
        JsonObject rule = new JsonObject();
        int interval = network == Network.REGTEST ? 150 : 840_000;
        if(coin == Coin.LITECOIN) {
            rule.addProperty("kind", "halving");
            rule.addProperty("interval", interval);
            rule.addProperty("initial", 50 * COIN_UNITS);
        } else {
            rule.addProperty("kind", "none");
        }
        for(int height : heights) {
            for(int h = Math.max(0, height - 1); h <= height + 1; h++) {
                OptionalLong coded = ChainRules.subsidy(coin, network, h);
                OptionalLong recorded = coin == Coin.LITECOIN ? OptionalLong.of(h / interval >= 64 ? 0 : (50 * COIN_UNITS) >> (h / interval)) : OptionalLong.empty();
                Assertions.assertEquals(coded, recorded, coin + " reward rule at height " + h);
            }
        }
        return rule;
    }

    private static JsonObject result(TraceResult trace) {
        JsonObject result = new JsonObject();
        result.addProperty("verdict", trace.verdict().name());
        JsonArray found = new JsonArray();
        for(Found f : trace.found()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("inscriptionId", f.inscriptionId());
            entry.addProperty("offset", f.offset());
            entry.addProperty("number", f.number());
            entry.addProperty("mintTxid", f.mintTxid().toString());
            entry.addProperty("mintHeight", f.mintHeight());
            entry.addProperty("mintTime", f.mintTime());
            found.add(entry);
        }
        result.add("found", found);
        result.addProperty("reason", trace.reason());
        JsonArray open = new JsonArray();
        for(Piece piece : trace.open()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("txid", piece.txid().toString());
            entry.addProperty("vout", piece.vout());
            entry.addProperty("start", piece.range().start());
            entry.addProperty("end", piece.range().end());
            entry.addProperty("coinOffset", piece.coinOffset());
            open.add(entry);
        }
        result.add("open", open);
        result.addProperty("lookups", trace.lookups());
        result.addProperty("usedChain", trace.usedChain());
        return result;
    }

    private static JsonObject object(Map<String, JsonElement> calls) {
        JsonObject object = new JsonObject();
        calls.forEach(object::add);
        return object;
    }

    private static void record(String chain, Coin coin, Network network, int electrumPort, String ordUrl) throws Exception {
        Coin previousCoin = Coin.beginScope(coin);
        Network previousNetwork = Network.get();
        Network.set(network);
        //TRACKER_ELECTRUM_PORT moves the server off its usual port (Windows can reserve 50105 under WSL's mirrored networking)
        String portOverride = System.getenv("TRACKER_ELECTRUM_PORT");
        try(Electrum electrum = new Electrum(portOverride == null ? electrumPort : Integer.parseInt(portOverride))) {
            String wantedTip = System.getenv("TRACKER_TIP");
            for(int i = 0; wantedTip != null && i < 90 && electrum.tip() < Integer.parseInt(wantedTip); i++) {
                Thread.sleep(2_000);
            }
            int tip = electrum.tip();
            Assertions.assertTrue(wantedTip == null || tip >= Integer.parseInt(wantedTip), chain + " Electrum server stuck at " + tip);
            OrdClient ord = new OrdClient(ordUrl);
            ord.status();
            System.out.println(chain + ": indexer dialect " + ord.dialect() + ", electrum tip " + tip);

            //The coins: each listed inscription's location, then that transaction's other unspent outputs
            List<String> ids = listedInscriptions(ordUrl);
            Assertions.assertFalse(ids.isEmpty(), chain + ": the indexer lists no inscriptions");
            Set<String> coins = new LinkedHashSet<>();
            for(String id : ids) {
                String satpoint = ord.inscription(id).satpoint();
                coins.add(satpoint.substring(0, satpoint.lastIndexOf(':')));
            }
            for(String location : new ArrayList<>(coins)) {
                Sha256Hash txid = Sha256Hash.wrap(location.substring(0, 64));
                Transaction tx = new Transaction(Utils.hexToBytes(electrum.call("blockchain.transaction.get", txid.toString()).getAsString()));
                for(int vout = 0; vout < tx.getOutputs().size(); vout++) {
                    if(tx.getOutputs().get(vout).getValue() > 0 && !coins.contains(txid + ":" + vout) && electrum.unspent(tx, vout)) {
                        coins.add(txid + ":" + vout);
                    }
                }
            }

            RecordingSource source = new RecordingSource(electrum, tip);
            JsonArray traced = new JsonArray();
            Map<String, int[]> counts = new TreeMap<>();
            for(String outpoint : coins) {
                Sha256Hash txid = Sha256Hash.wrap(outpoint.substring(0, 64));
                int vout = Integer.parseInt(outpoint.substring(65));
                for(int[] run : RUNS) {
                    boolean walk = run[1] == 1;
                    //A fresh tracer, cache and no known results per trace, so a trace's lookups do not depend on the ones before
                    TraceResult trace = new InscriptionTracer(coin, network, source, KnownResults.NONE, new TxCache())
                            .trace(txid, vout, List.of(), run[0], walk);
                    System.out.println(chain + " " + outpoint + " budget " + run[0] + (walk ? " walk" : "") + ": " + trace.verdict() + " "
                            + trace.found().stream().map(Found::inscriptionId).toList() + (trace.reason() == null ? "" : " (" + trace.reason() + ")") + ", "
                            + trace.lookups() + " lookups");
                    JsonObject entry = new JsonObject();
                    entry.addProperty("txid", txid.toString());
                    entry.addProperty("vout", vout);
                    entry.addProperty("budget", run[0]);
                    entry.addProperty("walkBlocks", walk);
                    entry.add("result", result(trace));
                    traced.add(entry);
                    counts.computeIfAbsent(run[0] + (walk ? " walk" : ""), k -> new int[Verdict.values().length])[trace.verdict().ordinal()]++;
                }
            }
            Assertions.assertEquals(tip, electrum.tip(), chain + ": the chain moved while recording");
            for(JsonElement answer : source.transactions.values()) {
                Assertions.assertFalse(answer.getAsJsonObject().has("height") && answer.getAsJsonObject().get("height").getAsInt() <= 0,
                        chain + ": an unconfirmed transaction was traced; mine a block first");
            }

            JsonObject calls = new JsonObject();
            calls.add("transaction", object(source.transactions));
            calls.add("blockFees", object(source.blockFees));
            calls.add("txidAt", object(source.txidAt));
            calls.add("blockHash", object(source.blockHash));
            JsonObject recording = new JsonObject();
            recording.addProperty("chain", chain);
            recording.addProperty("coin", coin.name());
            recording.addProperty("network", network.name());
            recording.addProperty("tip", tip);
            recording.addProperty("firstInscriptionHeight", ChainRules.firstInscriptionHeight(coin, network));
            recording.add("subsidyRule", subsidyRule(coin, network, source.coinbaseHeights));
            recording.add("calls", calls);
            recording.add("coins", traced);

            Path out = Path.of(System.getenv("TRACKER_OUT"), chain + ".json");
            Files.createDirectories(out.getParent());
            Gson gson = new GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create();
            Files.writeString(out, gson.toJson(recording) + "\n", StandardCharsets.UTF_8);
            for(Map.Entry<String, int[]> count : counts.entrySet()) {
                System.out.println(chain + " budget " + count.getKey() + ": " + count.getValue()[Verdict.INSCRIBED.ordinal()] + " inscribed, "
                        + count.getValue()[Verdict.CLEAN.ordinal()] + " clean, " + count.getValue()[Verdict.UNCHECKED.ordinal()] + " unchecked");
            }
            System.out.println(chain + ": " + coins.size() + " coins, " + source.transactions.size() + " transactions, " + source.txidAt.size()
                    + " block positions recorded to " + out);
        } finally {
            Network.set(previousNetwork);
            Coin.endScope(previousCoin);
        }
    }

    @Test
    public void recordChain() throws Exception {
        String chain = System.getenv("TRACKER_CHAIN");
        switch(chain) {
            case "litecoin" -> record(chain, Coin.LITECOIN, Network.REGTEST, 50105, "http://127.0.0.1:8085");
            case "dogecoin" -> record(chain, Coin.DOGECOIN, Network.TESTNET, 40111, "http://127.0.0.1:8091");
            case "pepecoin" -> record(chain, Coin.PEPECOIN, Network.TESTNET, 40112, "http://127.0.0.1:8092");
            case "junkcoin" -> record(chain, Coin.JUNKCOIN, Network.TESTNET, 40113, "http://127.0.0.1:8093");
            case "bellscoin" -> record(chain, Coin.BELLSCOIN, Network.REGTEST, 40114, "http://127.0.0.1:8094");
            default -> Assertions.fail("unknown chain " + chain);
        }
    }
}
