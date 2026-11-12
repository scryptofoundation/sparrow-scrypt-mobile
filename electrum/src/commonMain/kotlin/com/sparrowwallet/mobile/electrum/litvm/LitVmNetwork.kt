package com.sparrowwallet.mobile.electrum.litvm

/**
 * The LitVM networks the phone reads, as the desktop's LitVmNetwork names them. Only the LiteForge testnet: LitVM
 * mainnet has no published chain id or RPC endpoint, so there is deliberately none here. LiteForge moved from Caldera to
 * Zeeve in November 2026; these are the endpoints the official hub uses (chain 4441).
 */
enum class LitVmNetwork(val title: String, val chainId: Long, val rpcUrl: String, val explorerUrl: String) {
    LITEFORGE_TESTNET("LitVM LiteForge Testnet", 4441L, "https://rpc.testnet.liteforge.zeeve.net/", "https://explorer.testnet.liteforge.zeeve.net")
}
