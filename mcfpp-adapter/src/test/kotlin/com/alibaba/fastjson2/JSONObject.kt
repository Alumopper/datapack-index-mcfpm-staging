package com.alibaba.fastjson2

public class JSONObject(public val source: String) {
    public companion object {
        @JvmStatic
        public fun parse(source: String): JSONObject = JSONObject(source)
    }
}
