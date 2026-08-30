package top.mcfpp.io

import java.io.InputStream

public object LibBinReader {
    public val loaded: MutableList<String> = mutableListOf()

    public fun readFromStream(stream: InputStream) {
        loaded.add(stream.readAllBytes().decodeToString())
    }
}
