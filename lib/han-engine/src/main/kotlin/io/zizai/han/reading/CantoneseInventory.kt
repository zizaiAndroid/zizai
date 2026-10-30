/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.han.reading

/**
 * The toneless Cantonese syllables in Jyutping: every one Unihan 18.0's kCantonese uses (659).
 * Order doesn't matter, the table sorts them.
 *
 * Changing this list changes syllable ids, which are stored in han-yue.bin: rebuild it when you do.
 */
internal object CantoneseInventory {
    val SPELLINGS: List<String> = """
        aa aai aak aan aang aap aat aau ai ak am an ang ap au baa baai baak baan baat baau bai bak bam ban
        bang bap bat bau be bei beng bik bin bing bit biu bo bok bong bou bui buk bun bung but caa caai
        caak caam caan caang caap caat caau cai cak cam can cang cap cat cau ce cek ceoi ceon ceot ci cik
        cim cin cing cip cit ciu co coek coeng coi cok cong cou cuk cung cyu cyun cyut daa daai daam daan
        daap daat dai dak dam dan dang dap dat dau de dei dek deng deoi deon deot di dik dim din ding dip
        dit diu do doe doek doeng doi dok dong dou duk dun dung dyun dyut e eot faa faai faan faat fai fan
        fang fat fau fe fei fik fit fiu fo fok fong fu fui fuk fun fung fut gaa gaai gaak gaam gaan gaang
        gaap gaat gaau gai gak gam gan gang gap gat gau ge gei geng geoi gi gik gim gin ging gip git giu go
        goe goek goeng goi gok gon gong got gou gu gui guk gun gung gwaa gwaai gwaak gwaan gwaang gwaat
        gwai gwan gwang gwat gwe gwi gwik gwing gwit gwo gwok gwong gyun haa haai haak haam haan haang haap
        haau hai hak ham han hang hap hat hau he hei hek heoi hik him hin hing hip hit hiu hm ho hoe hoeng
        hoi hok hon hong hot hou huk hung hyun hyut jaa jaai jaak jaang jaap jaau jai jam jan jap jat jau
        je jeoi jeon ji jik jim jin jing jip jit jiu jo joek joeng ju juk jung jyu jyun jyut kaa kaai kaak
        kaau kai kak kam kan kang kap kat kau ke kei kek keoi keu kik kim kin king kip kit kiu ko koek
        koeng koi kok kong ku kui kuk kung kut kwaa kwaai kwaang kwaat kwai kwan kwang kwik kwok kwong kyun
        kyut laa laai laak laam laan laang laap laat laau lai lak lam lan lang lap lat lau le lei lek leng
        leoi leon leot leu li lik lim lin ling lip lit liu lo loe loek loeng loi lok long lou luk lung lyun
        lyut m maa maai maak maan maang maat maau mai mak man mang mat mau me mei meng mik min ming mit miu
        mo mok mong mou mui muk mun mung mut naa naai naam naan naap naat naau nai nak nam nan nang nap nau
        ne nei neng neoi neot ng ngaa ngaai ngaak ngaam ngaan ngaang ngaap ngaat ngaau ngai ngak ngam ngan
        ngang ngap ngat ngau ngit ngo ngoi ngok ngon ngong ngou ngung ni nik nim nin ning nip nit niu no
        noek noeng noi nok nong nou nuk nung nyun o oi ok on ong ou paa paai paak paan paang paat paau pai
        pan pang pat pau pe pei peng pik pin ping pit piu po pok pong pou pui pun pung put saa saai saak
        saam saan saang saap saat saau sai sak sam san sang sap sat sau se sei sek seng seoi seon seot si
        sik sim sin sing sip sit siu so soe soek soeng soi sok song sou suk sung syu syun syut taa taai
        taam taan taap taat tai tam tan tang tap tau tek teng teoi teon teot ti tik tim tin ting tip tit
        tiu to toi tok tong tou tuk tung tyun tyut uk ung waa waai waak waan waang waat wai wak wan wang
        wat we wen wet wik wing wit wo wok wong wu wui wun wut zaa zaai zaak zaam zaan zaang zaap zaat zaau
        zai zak zam zan zang zap zat zau ze zek zeng zeoi zeon zeot zi zik zim zin zing zip zit ziu zo zoek
        zoeng zoi zok zong zou zuk zung zyu zyun zyut
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotEmpty() }
}
