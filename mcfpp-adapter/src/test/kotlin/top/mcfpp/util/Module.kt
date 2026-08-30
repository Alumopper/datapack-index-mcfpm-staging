package top.mcfpp.util

import com.alibaba.fastjson2.JSONObject
import java.nio.file.Path

public enum class ModuleType {
    ZIP,
    JAR,
    DIR,
    INNER,
}

public class Module(public val id: String) {
    public var type: ModuleType = ModuleType.DIR
    public var resourcePath: Path = Path.of(".")

    public companion object {
        public fun fromJson(json: JSONObject): ArrayList<Module> {
            val id = requireNotNull(Regex("\\\"([^\\\"]+)\\\"").find(json.source)?.groupValues?.get(1))
            return arrayListOf(Module(id))
        }
    }
}
