package com.tarvos.clearaudio

/**
 * 44.1-kHz-Konstanten des Full-Chain-Ports (KNOWLEDGE 293, validiert
 * gegen die 16-Block-Emulator-Referenz fullchain_ref44.pkl:
 * FAIL=0, ok=4066, dust=30, maxDust 2.6e-35).
 * Ratenabhaengig sind NUR K65 (Upcon-FIR-Kern), TAPS (Analyse-D-FIR)
 * und ROD_SA (Stage-A-Taps, Firmware-Tabelle 0x163408 statt 0x16346c).
 * GAIN/Biquad/ROD_P1 sind rateninvariant (Adress-Trace-Diff 44.1 vs 48).
 */
object FC44 {
    @JvmField val GAIN = Float.fromBits(1061479129)
    @JvmField val BQ_A = Float.fromBits(-1078017871)
    @JvmField val BQ_B = Float.fromBits(1058706923)
    @JvmField val BQ_M2 = Float.fromBits(-1073741824)
    @JvmField val BQ_ONE = Float.fromBits(1065353216)
    @JvmField val K65 = floatArrayOf(
        Float.fromBits(1085423616), Float.fromBits(-1069010587), Float.fromBits(-1089595040), Float.fromBits(1063688157), Float.fromBits(1057154418), Float.fromBits(-1094361525), Float.fromBits(-1092390451), Float.fromBits(1040802865),
        Float.fromBits(1052322869), Float.fromBits(1009695746), Float.fromBits(-1098152727), Float.fromBits(-1111657021), Float.fromBits(1044249211), Float.fromBits(1040574168), Float.fromBits(-1109586836), Float.fromBits(-1106399203),
        Float.fromBits(1027031469), Float.fromBits(1040237646), Float.fromBits(988770332), Float.fromBits(-1110400940), Float.fromBits(-1123884425), Float.fromBits(1033318776), Float.fromBits(1027889578), Float.fromBits(-1119909574),
        Float.fromBits(-1118143951), Float.fromBits(1019365783), Float.fromBits(1029079720), Float.fromBits(-1145230574), Float.fromBits(-1119056428), Float.fromBits(-1143252625), Float.fromBits(1028735383), Float.fromBits(1016727261),
        Float.fromBits(-1111031194), Float.fromBits(1034584338), Float.fromBits(-1127156085), Float.fromBits(-1152654311), Float.fromBits(994308687), Float.fromBits(764763716), Float.fromBits(-1345438619), Float.fromBits(799160902),
        Float.fromBits(-1366794889), Float.fromBits(-1363961671), Float.fromBits(781975421), Float.fromBits(-1381553245), Float.fromBits(-1406984999), Float.fromBits(740082133), Float.fromBits(0), Float.fromBits(0),
        Float.fromBits(0), Float.fromBits(0), Float.fromBits(0), Float.fromBits(0), Float.fromBits(0), Float.fromBits(0), Float.fromBits(0), Float.fromBits(0),
        Float.fromBits(0), Float.fromBits(0), Float.fromBits(0), Float.fromBits(0), Float.fromBits(0), Float.fromBits(0), Float.fromBits(0), Float.fromBits(0),
        Float.fromBits(0))
    @JvmField val TAPS = floatArrayOf(
        Float.fromBits(1057656248), Float.fromBits(-1102445682), Float.fromBits(-1096785008), Float.fromBits(1050629034), Float.fromBits(-1218609152), Float.fromBits(-1111811363), Float.fromBits(995770496), Float.fromBits(-1129405876),
        Float.fromBits(1033961974), Float.fromBits(-1126826902), Float.fromBits(-1127617300))
    @JvmField val ROD_SA = floatArrayOf(
        Float.fromBits(1056041746), Float.fromBits(1050775686), Float.fromBits(1021055577), Float.fromBits(-1110912233), Float.fromBits(-1127416490), Float.fromBits(1028698291), Float.fromBits(1018548257), Float.fromBits(-1125264044),
        Float.fromBits(-1130814751), Float.fromBits(1015400018), Float.fromBits(1014232302), Float.fromBits(-1140136469), Float.fromBits(-1137272222), Float.fromBits(997314351), Float.fromBits(1006526886), Float.fromBits(-1166004753),
        Float.fromBits(-1146985732), Float.fromBits(-1179589375), Float.fromBits(993589481), Float.fromBits(977940067), Float.fromBits(-1162667280), Float.fromBits(-1170368095), Float.fromBits(973965900), Float.fromBits(975804071),
        Float.fromBits(961758418))
    @JvmField val ROD_P1 = floatArrayOf(
        Float.fromBits(1057042310), Float.fromBits(-1096698260), Float.fromBits(-1147897403), Float.fromBits(1036683298), Float.fromBits(998962899), Float.fromBits(-1118442394), Float.fromBits(-1149738022), Float.fromBits(1023066927),
        Float.fromBits(995274788), Float.fromBits(-1131167100), Float.fromBits(-1154905704), Float.fromBits(1009167747), Float.fromBits(989491908), Float.fromBits(-1146463243), Float.fromBits(-1162909646), Float.fromBits(992486971),
        Float.fromBits(983475968))
}
