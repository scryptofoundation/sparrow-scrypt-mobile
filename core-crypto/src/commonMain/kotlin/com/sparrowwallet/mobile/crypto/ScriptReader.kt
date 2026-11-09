package com.sparrowwallet.mobile.crypto

/** Splits a Bitcoin-family script into opcodes and pushed data. */
object ScriptReader {
    /** [data] is the pushed bytes (empty for OP_0), or null for any opcode that is not a push. */
    class Chunk(val opcode: Int, val data: ByteArray?)

    /** The script's chunks, or null when a push runs past the end of the script. */
    fun chunks(script: ByteArray): List<Chunk>? {
        val chunks = mutableListOf<Chunk>()
        return if(read(script, chunks)) chunks else null
    }

    /** The chunks read before a push ran past the end of the script (all of them when none did), as drongo's Script keeps them. */
    fun chunksUpToError(script: ByteArray): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        read(script, chunks)
        return chunks
    }

    /** Reads into chunks; false when a push runs past the end of the script. */
    private fun read(script: ByteArray, chunks: MutableList<Chunk>): Boolean {
        var i = 0
        while(i < script.size) {
            val op = script[i].toInt() and 0xFF
            i++
            val length = when {
                op == 0x00 -> 0
                op in 0x01..0x4b -> op
                op == 0x4c -> { if(i + 1 > script.size) return false; (script[i].toInt() and 0xFF).also { i += 1 } }
                op == 0x4d -> { if(i + 2 > script.size) return false; ((script[i].toInt() and 0xFF) or ((script[i + 1].toInt() and 0xFF) shl 8)).also { i += 2 } }
                op == 0x4e -> {
                    if(i + 4 > script.size) return false
                    val n = (0 until 4).fold(0L) { acc, k -> acc or ((script[i + k].toLong() and 0xFF) shl (8 * k)) }
                    i += 4
                    if(n > script.size) return false
                    n.toInt()
                }
                else -> { chunks.add(Chunk(op, null)); continue }
            }
            if(i + length > script.size) return false
            chunks.add(Chunk(op, script.copyOfRange(i, i + length)))
            i += length
        }
        return true
    }
}
