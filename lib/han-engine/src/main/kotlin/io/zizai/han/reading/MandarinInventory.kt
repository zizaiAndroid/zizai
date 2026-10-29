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
 * The toneless Mandarin syllables used in modern dictionaries, spelled with `v` for ü. Grouped by final
 * for review; order doesn't matter, the table sorts them.
 *
 * Changing this list changes syllable ids, which are stored in lexicon files: bump
 * [io.zizai.han.HanEngine.LEXICON_FORMAT] and rebuild the data when you do.
 */
internal object MandarinInventory {
    val SPELLINGS: List<String> = """
        a ba pa ma fa da ta na la ga ka ha zha cha sha za ca sa
        ai bai pai mai dai tai nai lai gai kai hai zhai chai shai zai cai sai
        an ban pan man fan dan tan nan lan gan kan han zhan chan shan ran zan can san
        ang bang pang mang fang dang tang nang lang gang kang hang zhang chang shang rang zang cang sang
        ao bao pao mao dao tao nao lao gao kao hao zhao chao shao rao zao cao sao
        e me de te ne le ge ke he zhe che she re ze ce se
        ei bei pei mei fei dei tei nei lei gei kei hei zhei shei zei
        en ben pen men fen den nen gen ken hen zhen chen shen ren zen cen sen
        eng beng peng meng feng deng teng neng leng geng keng heng zheng cheng sheng reng zeng ceng seng
        er
        yi bi pi mi di ti ni li ji qi xi
        zhi chi shi ri zi ci si
        ya dia lia jia qia xia
        yan bian pian mian dian tian nian lian jian qian xian
        yang niang liang jiang qiang xiang
        yao biao piao miao diao tiao niao liao jiao qiao xiao fiao
        ye bie pie mie die tie nie lie jie qie xie
        yin bin pin min nin lin jin qin xin
        ying bing ping ming ding ting ning ling jing qing xing
        yong jiong qiong xiong
        you miu diu niu liu jiu qiu xiu
        o bo po mo fo lo yo
        dong tong nong long gong kong hong zhong chong rong zong cong song
        ou pou mou fou dou tou nou lou gou kou hou zhou chou shou rou zou cou sou
        wu bu pu mu fu du tu nu lu gu ku hu zhu chu shu ru zu cu su
        wa gua kua hua zhua chua shua rua
        wai guai kuai huai zhuai chuai shuai
        wan duan tuan nuan luan guan kuan huan zhuan chuan shuan ruan zuan cuan suan
        wang guang kuang huang zhuang chuang shuang
        wei dui tui gui kui hui zhui chui shui rui zui cui sui
        wen dun tun lun gun kun hun zhun chun shun run zun cun sun
        weng
        wo duo tuo nuo luo guo kuo huo zhuo chuo shuo ruo zuo cuo suo
        yu nv lv ju qu xu
        yuan juan quan xuan
        yue nve lve jue que xue
        yun jun qun xun
        m n ng hm hng
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotEmpty() }
}
