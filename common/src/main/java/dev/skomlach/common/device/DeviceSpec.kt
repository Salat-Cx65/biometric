package dev.skomlach.common.device

import com.google.gson.annotations.SerializedName


data class DeviceSpec(
    @SerializedName("brand") var brand: String? = null,
    @SerializedName("codename") var codename: String? = null,
    @SerializedName("name") var name: String? = null,
    @SerializedName("recoveries") var recoveries: ArrayList<Recoveries> = arrayListOf(),
    @SerializedName("roms") var roms: ArrayList<Roms> = arrayListOf(),
    @SerializedName("specs") var specs: Specs? = Specs()
)