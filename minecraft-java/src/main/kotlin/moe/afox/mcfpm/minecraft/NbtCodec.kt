package moe.afox.mcfpm.minecraft

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal sealed interface NbtTag {
    val type: Int

    data class ByteTag(val value: Byte) : NbtTag { override val type = 1 }
    data class ShortTag(val value: Short) : NbtTag { override val type = 2 }
    data class IntTag(val value: Int) : NbtTag { override val type = 3 }
    data class LongTag(val value: Long) : NbtTag { override val type = 4 }
    data class FloatTag(val value: Float) : NbtTag { override val type = 5 }
    data class DoubleTag(val value: Double) : NbtTag { override val type = 6 }
    data class ByteArrayTag(val value: ByteArray) : NbtTag { override val type = 7 }
    data class StringTag(val value: String) : NbtTag { override val type = 8 }
    data class ListTag(val elementType: Int, val value: MutableList<NbtTag>) : NbtTag { override val type = 9 }
    data class CompoundTag(val value: LinkedHashMap<String, NbtTag>) : NbtTag { override val type = 10 }
    data class IntArrayTag(val value: IntArray) : NbtTag { override val type = 11 }
    data class LongArrayTag(val value: LongArray) : NbtTag { override val type = 12 }
}

internal data class NamedNbt(
    val name: String,
    val root: NbtTag.CompoundTag,
    val compressed: Boolean,
)

internal object NbtCodec {
    private const val MAX_DEPTH = 64
    private const val MAX_COLLECTION_LENGTH = 16_777_216

    fun decode(bytes: ByteArray): NamedNbt {
        val compressed = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        val rawInput = ByteArrayInputStream(bytes)
        val input = DataInputStream(if (compressed) GZIPInputStream(rawInput) else rawInput)
        input.use {
            val type = it.readUnsignedByte()
            require(type == 10) { "NBT root must be a compound tag" }
            val name = it.readUTF()
            val root = readPayload(it, type, 0) as NbtTag.CompoundTag
            return NamedNbt(name, root, compressed)
        }
    }

    fun encode(document: NamedNbt): ByteArray {
        val raw = ByteArrayOutputStream()
        DataOutputStream(raw).use { output ->
            output.writeByte(10)
            output.writeUTF(document.name)
            writePayload(output, document.root)
        }
        if (!document.compressed) return raw.toByteArray()
        val compressed = ByteArrayOutputStream()
        GZIPOutputStream(compressed).use { it.write(raw.toByteArray()) }
        return compressed.toByteArray()
    }

    private fun readPayload(input: DataInputStream, type: Int, depth: Int): NbtTag {
        require(depth <= MAX_DEPTH) { "NBT nesting exceeds safety limit" }
        return when (type) {
            1 -> NbtTag.ByteTag(input.readByte())
            2 -> NbtTag.ShortTag(input.readShort())
            3 -> NbtTag.IntTag(input.readInt())
            4 -> NbtTag.LongTag(input.readLong())
            5 -> NbtTag.FloatTag(input.readFloat())
            6 -> NbtTag.DoubleTag(input.readDouble())
            7 -> NbtTag.ByteArrayTag(ByteArray(readLength(input)).also(input::readFully))
            8 -> NbtTag.StringTag(input.readUTF())
            9 -> {
                val elementType = input.readUnsignedByte()
                val length = readLength(input)
                require(elementType in 1..12 || length == 0) { "Invalid NBT list element type: $elementType" }
                NbtTag.ListTag(elementType, MutableList(length) { readPayload(input, elementType, depth + 1) })
            }
            10 -> {
                val values = linkedMapOf<String, NbtTag>()
                while (true) {
                    val childType = input.readUnsignedByte()
                    if (childType == 0) break
                    require(childType in 1..12) { "Invalid NBT tag type: $childType" }
                    val name = input.readUTF()
                    require(values.put(name, readPayload(input, childType, depth + 1)) == null) {
                        "Duplicate NBT compound key: $name"
                    }
                }
                NbtTag.CompoundTag(LinkedHashMap(values))
            }
            11 -> NbtTag.IntArrayTag(IntArray(readLength(input)) { input.readInt() })
            12 -> NbtTag.LongArrayTag(LongArray(readLength(input)) { input.readLong() })
            else -> throw IllegalArgumentException("Invalid NBT tag type: $type")
        }
    }

    private fun writePayload(output: DataOutputStream, tag: NbtTag) {
        when (tag) {
            is NbtTag.ByteTag -> output.writeByte(tag.value.toInt())
            is NbtTag.ShortTag -> output.writeShort(tag.value.toInt())
            is NbtTag.IntTag -> output.writeInt(tag.value)
            is NbtTag.LongTag -> output.writeLong(tag.value)
            is NbtTag.FloatTag -> output.writeFloat(tag.value)
            is NbtTag.DoubleTag -> output.writeDouble(tag.value)
            is NbtTag.ByteArrayTag -> {
                output.writeInt(tag.value.size)
                output.write(tag.value)
            }
            is NbtTag.StringTag -> output.writeUTF(tag.value)
            is NbtTag.ListTag -> {
                output.writeByte(tag.elementType)
                output.writeInt(tag.value.size)
                tag.value.forEach { child ->
                    require(child.type == tag.elementType) { "NBT list contains a mismatched tag type" }
                    writePayload(output, child)
                }
            }
            is NbtTag.CompoundTag -> {
                tag.value.forEach { (name, child) ->
                    output.writeByte(child.type)
                    output.writeUTF(name)
                    writePayload(output, child)
                }
                output.writeByte(0)
            }
            is NbtTag.IntArrayTag -> {
                output.writeInt(tag.value.size)
                tag.value.forEach(output::writeInt)
            }
            is NbtTag.LongArrayTag -> {
                output.writeInt(tag.value.size)
                tag.value.forEach(output::writeLong)
            }
        }
    }

    private fun readLength(input: DataInputStream): Int = input.readInt().also { length ->
        require(length in 0..MAX_COLLECTION_LENGTH) { "NBT collection length exceeds safety limit: $length" }
    }
}

internal object MinecraftLevelDat {
    fun dataVersion(bytes: ByteArray): Int? {
        val root = NbtCodec.decode(bytes).root
        val data = root.value["Data"] as? NbtTag.CompoundTag ?: root
        return (data.value["DataVersion"] as? NbtTag.IntTag)?.value
    }

    fun updateEnabledDataPacks(bytes: ByteArray, managedFileNames: List<String>): ByteArray {
        val document = NbtCodec.decode(bytes)
        val data = document.root.value["Data"] as? NbtTag.CompoundTag
            ?: throw IllegalArgumentException("level.dat is missing the Data compound")
        val dataPacks = data.value["DataPacks"] as? NbtTag.CompoundTag
            ?: throw IllegalArgumentException("level.dat is missing the DataPacks compound")
        val enabled = dataPacks.value["Enabled"] as? NbtTag.ListTag
            ?: throw IllegalArgumentException("level.dat is missing DataPacks.Enabled")
        require(enabled.elementType == 8) { "level.dat DataPacks.Enabled must be a string list" }
        val before = enabled.value.map { (it as NbtTag.StringTag).value }
        val unmanaged = before.filterNot { it.startsWith("file/mcfpm-") }
        val desired = managedFileNames.map { "file/$it" }
        enabled.value.clear()
        enabled.value += (unmanaged + desired).distinct().map(NbtTag::StringTag)
        return NbtCodec.encode(document)
    }

    fun enabledDataPacks(bytes: ByteArray): List<String> {
        val root = NbtCodec.decode(bytes).root
        val data = root.value["Data"] as? NbtTag.CompoundTag
            ?: throw IllegalArgumentException("level.dat is missing the Data compound")
        val dataPacks = data.value["DataPacks"] as? NbtTag.CompoundTag
            ?: throw IllegalArgumentException("level.dat is missing the DataPacks compound")
        val enabled = dataPacks.value["Enabled"] as? NbtTag.ListTag
            ?: throw IllegalArgumentException("level.dat is missing DataPacks.Enabled")
        require(enabled.elementType == 8) { "level.dat DataPacks.Enabled must be a string list" }
        return enabled.value.map { (it as NbtTag.StringTag).value }
    }
}
