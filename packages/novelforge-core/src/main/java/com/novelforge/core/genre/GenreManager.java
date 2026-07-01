package com.novelforge.core.genre;

import com.novelforge.core.models.GenreProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * GenreManager — loads and provides genre profiles.
 * Built-in profiles for Chinese web novel genres and English genres.
 * Custom profiles can be loaded from book project config directory.
 */
public class GenreManager {

    private static final Logger log = LoggerFactory.getLogger(GenreManager.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, GenreProfile> profiles = new HashMap<>();

    public GenreManager() {
        loadBuiltInProfiles();
    }

    /** Load built-in genre profiles from resources */
    private void loadBuiltInProfiles() {
        String[] builtInKeys = {
            "xuanhuan", "xianxia", "urban", "horror", "romance-zh",
            "fantasy", "thriller", "romance-en", "scifi", "mystery"
        };

        for (String key : builtInKeys) {
            try {
                String resourcePath = "/genres/" + key + ".json";
                InputStream is = GenreManager.class.getResourceAsStream(resourcePath);
                if (is != null) {
                    GenreProfile profile = mapper.readValue(is, GenreProfile.class);
                    profiles.put(key, profile);
                    log.debug("Loaded built-in genre: {}", key);
                } else {
                    // Generate default profile if JSON file not found
                    profiles.put(key, createDefaultProfile(key));
                    log.debug("Created default genre: {}", key);
                }
            } catch (Exception e) {
                log.warn("Failed to load genre {}, creating default", key, e);
                profiles.put(key, createDefaultProfile(key));
            }
        }

        log.info("GenreManager initialized with {} profiles", profiles.size());
    }

    /** Load custom genre from book project config */
    public void loadCustomGenre(Path customGenreFile) {
        try {
            GenreProfile profile = mapper.readValue(Files.newInputStream(customGenreFile), GenreProfile.class);
            profiles.put(profile.getKey(), profile);
            log.info("Loaded custom genre: {}", profile.getKey());
        } catch (Exception e) {
            log.error("Failed to load custom genre from {}", customGenreFile, e);
        }
    }

    /** Get genre profile by key */
    public GenreProfile getGenre(String key) {
        return profiles.get(key);
    }

    /** List all available genre keys */
    public String[] listGenreKeys() {
        return profiles.keySet().toArray(new String[0]);
    }

    /** Create a basic default profile for a genre key */
    private GenreProfile createDefaultProfile(String key) {
        GenreProfile p = new GenreProfile();
        p.setKey(key);

        switch (key) {
            case "xuanhuan" -> {
                p.setLabel("玄幻");
                p.setLanguage("zh");
                p.setOutlineTemplate("经典玄幻升级流：废材开局→奇遇觉醒→层层升级→最终巅峰");
                p.setTropes(new String[]{"金手指", "等级体系", "天才 vs 废材", "家族仇恨", "秘境探索", "拍卖会", "传承争夺"});
                p.setAvoidanceList(new String[]{"主角太完美无弱点", "女主纯花瓶", "升级太快失去张力", "反派智商为零"});
                p.setNamingConvention("中式玄幻命名：姓氏+霸气名（如：萧炎、林动、石昊）");
                p.setPacingRules("每3-5章一个小高潮，每10-15章一个大转折，章节结尾必有悬念");
                p.setUpgradeSystem("修炼等级：炼气→筑基→金丹→元婴→化神→渡劫→大乘→飞升，每级分1-9重");
            }
            case "xianxia" -> {
                p.setLabel("仙侠");
                p.setLanguage("zh");
                p.setOutlineTemplate("仙侠修真流：凡人修仙→宗门历练→渡劫飞升→仙界争斗");
                p.setTropes(new String[]{"修仙", "宗门", "渡劫", "法宝", "仙缘", "因果", "道心"});
                p.setAvoidanceList(new String[]{"修仙过于世俗化", "因果逻辑混乱", "渡劫太随意"});
                p.setNamingConvention("仙侠命名：雅致古风（如：陆雪琪、韩立、沈夜）");
                p.setPacingRules("修仙节奏偏慢热，前期铺垫长，中期加速，后期宏大");
                p.setUpgradeSystem("修仙等级：练气→筑基→结丹→元婴→化神→合体→大乘→渡劫");
            }
            case "urban" -> {
                p.setLabel("都市");
                p.setLanguage("zh");
                p.setOutlineTemplate("都市逆袭流：普通人→机遇觉醒→商战/权谋→都市巅峰");
                p.setTropes(new String[]{"重生", "穿越", "系统", "商战", "医术", "黑科技", "权谋"});
                p.setAvoidanceList(new String[]{"主角太装", "女角色过多无个性", "脱离现实常识"});
                p.setNamingConvention("都市现代命名：普通姓名（如：张伟、李凡、陈平）");
                p.setPacingRules("快节奏，每章都有小高潮，商业/权谋斗争层层推进");
                p.setUpgradeSystem("社会等级：普通→小有成就→一方大佬→行业巨头→国民级影响力");
            }
            case "horror" -> {
                p.setLabel("恐怖");
                p.setLanguage("zh");
                p.setOutlineTemplate("恐怖悬疑流：诡异事件→层层揭开→真相骇人");
                p.setTropes(new String[]{"灵异", "古宅", "诡异规则", "不可名状", "求生", "诅咒", "禁忌"});
                p.setAvoidanceList(new String[]{"恐怖感依赖血腥而非氛围", "解释过度破坏神秘感"});
                p.setNamingConvention("恐怖命名：有暗示性（如：陈默、苏幽、顾忌）");
                p.setPacingRules("缓慢递进恐怖感，每章揭开一点真相但引出更多疑问，结尾必留恐惧余韵");
                p.setUpgradeSystem("求生能力升级：感知→抵抗→对抗→掌控，但永远不完全安全");
            }
            case "romance-zh" -> {
                p.setLabel("言情");
                p.setLanguage("zh");
                p.setOutlineTemplate("言情甜宠/虐恋流：相遇→误会→心动→波折→圆满/BE");
                p.setTropes(new String[]{"霸道总裁", "契约婚姻", "青梅竹马", "前世今生", "误会", "追妻火葬场"});
                p.setAvoidanceList(new String[]{"感情线逻辑混乱", "配角抢戏", "甜蜜段落太短"});
                p.setNamingConvention("言情命名：温雅有气质（如：顾言清、沈亦泽、陆晚晚）");
                p.setPacingRules("每章都有感情推进，误会不过3章必须解开，大波折不超过2次");
                p.setUpgradeSystem("感情等级：初识→心动→暧昧→确认→磨合→深爱→生死相依");
            }
            case "fantasy" -> {
                p.setLabel("Fantasy");
                p.setLanguage("en");
                p.setOutlineTemplate("Hero's journey: Call to adventure → Trials → Growth → Final confrontation");
                p.setTropes(new String[]{"chosen one", "magic system", "quest", "mentor", "ancient prophecy", "dark lord"});
                p.setAvoidanceList(new String[]{"generic chosen one without twist", "magic without rules"});
                p.setNamingConvention("Fantasy naming: evocative, slightly archaic (e.g., Kael, Rowen, Aelith)");
                p.setPacingRules("Slow build in book 1, escalation in book 2, climactic in book 3");
                p.setUpgradeSystem("Power progression: Novice → Apprentice → Adept → Master → Legendary");
            }
            case "thriller" -> {
                p.setLabel("Thriller");
                p.setLanguage("en");
                p.setOutlineTemplate("Tension spiral: Normal life disrupted → escalating danger → twist reveal → survival");
                p.setTropes(new String[]{"conspiracy", "chase", "betrayal", "false lead", "clock ticking", "hidden identity"});
                p.setAvoidanceList(new String[]{"dumb protagonist", "convenience coincidences"});
                p.setNamingConvention("Modern realistic names");
                p.setPacingRules("Tight pacing, every chapter must raise stakes. Never let tension drop for more than 1 chapter");
                p.setUpgradeSystem("No traditional power system. Escalation through information and stakes");
            }
            case "romance-en" -> {
                p.setLabel("Romance");
                p.setLanguage("en");
                p.setOutlineTemplate("Meet → Conflict → Growth → Commitment");
                p.setTropes(new String[]{"meet-cute", "misunderstanding", "slow burn", "grand gesture", "love triangle"});
                p.setAvoidanceList(new String[]{"toxic dynamics portrayed as romantic", "no character growth"});
                p.setNamingConvention("Contemporary names reflecting setting");
                p.setPacingRules("Every chapter must advance the relationship or reveal character depth");
                p.setUpgradeSystem("Relationship stages: Interest → Attraction → Commitment → Deep love");
            }
            case "scifi" -> {
                p.setLabel("Sci-Fi");
                p.setLanguage("en");
                p.setOutlineTemplate("Discovery → Crisis → Solution at cost → New world order");
                p.setTropes(new String[]{"AI", "space travel", "dystopia", "first contact", "technology gone wrong"});
                p.setAvoidanceList(new String[]{"hard science without narrative", "tech-magic without explanation"});
                p.setNamingConvention("Futuristic or culturally diverse names");
                p.setPacingRules("Discovery chapters slow, crisis chapters fast, resolution balanced");
                p.setUpgradeSystem("Tech/knowledge progression: Ignorance → Awareness → Mastery → Innovation");
            }
            case "mystery" -> {
                p.setLabel("Mystery");
                p.setLanguage("en");
                p.setOutlineTemplate("Crime/disappearance → Investigation → Red herrings → True reveal");
                p.setTropes(new String[]{"whodunit", "locked room", "detective", "clues", "false confession", "hidden motive"});
                p.setAvoidanceList(new String[]{"solution revealed too early", "detective with no flaws"});
                p.setNamingConvention("Names that could belong to anyone (suspicion on all)");
                p.setPacingRules("Clues distributed evenly. 1 major clue per 3 chapters. False leads every 2 chapters");
                p.setUpgradeSystem("Evidence chain: Suspicion → Lead → Clue → Connection → Proof → Reveal");
            }
            default -> {
                p.setLabel(key);
                p.setLanguage("zh");
                p.setOutlineTemplate("自由创作，无固定模板");
                p.setTropes(new String[]{});
                p.setAvoidanceList(new String[]{});
                p.setNamingConvention("自由命名");
                p.setPacingRules("自由节奏");
                p.setUpgradeSystem("自由体系");
            }
        }

        return p;
    }
}
