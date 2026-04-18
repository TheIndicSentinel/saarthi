package com.saarthi.core.inference.model

enum class PackType(val displayNameKey: String, val loraFileName: String?) {
    BASE("pack_base", null),
    KNOWLEDGE("pack_knowledge", "knowledge_lora.bin"),
    MONEY("pack_money", "money_lora.bin"),
    KISAN("pack_kisan", "kisan_lora.bin"),
    FIELD_EXPERT("pack_field_expert", "fieldexpert_lora.bin"),
}
