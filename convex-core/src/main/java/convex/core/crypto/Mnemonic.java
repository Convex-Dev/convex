package convex.core.crypto;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;

import convex.core.data.ABlob;
import convex.core.data.Blob;
import convex.core.data.BlobBuilder;
import convex.core.data.Hash;
import convex.core.util.Utils;

/**
 * Static utility functions for Mnemonic encoding
 */
public class Mnemonic {

	// word list from https://tools.ietf.org/html/rfc1751
	private static final String[] WORDS = { "a", "abe", "ace", "act", "ad", "ada", "add", "ago", "aid", "aim", "air",
			"all", "alp", "am", "amy", "an", "ana", "and", "ann", "ant", "any", "ape", "aps", "apt", "arc", "are",
			"ark", "arm", "art", "as", "ash", "ask", "at", "ate", "aug", "auk", "ave", "awe", "awk", "awl", "awn", "ax",
			"aye", "bad", "bag", "bah", "bam", "ban", "bar", "bat", "bay", "be", "bed", "bee", "beg", "ben", "bet",
			"bey", "bib", "bid", "big", "bin", "bit", "bob", "bog", "bon", "boo", "bop", "bow", "boy", "bub", "bud",
			"bug", "bum", "bun", "bus", "but", "buy", "by", "bye", "cab", "cal", "cam", "can", "cap", "car", "cat",
			"caw", "cod", "cog", "col", "con", "coo", "cop", "cot", "cow", "coy", "cry", "cub", "cue", "cup", "cur",
			"cut", "dab", "dad", "dam", "dan", "dar", "day", "dee", "del", "den", "des", "dew", "did", "die", "dig",
			"din", "dip", "do", "doe", "dog", "don", "dot", "dow", "dry", "dub", "dud", "due", "dug", "dun", "ear",
			"eat", "ed", "eel", "egg", "ego", "eli", "elk", "elm", "ely", "em", "end", "est", "etc", "eva", "eve",
			"ewe", "eye", "fad", "fan", "far", "fat", "fay", "fed", "fee", "few", "fib", "fig", "fin", "fir", "fit",
			"flo", "fly", "foe", "fog", "for", "fry", "fum", "fun", "fur", "gab", "gad", "gag", "gal", "gam", "gap",
			"gas", "gay", "gee", "gel", "gem", "get", "gig", "gil", "gin", "go", "got", "gum", "gun", "gus", "gut",
			"guy", "gym", "gyp", "ha", "had", "hal", "ham", "han", "hap", "has", "hat", "haw", "hay", "he", "hem",
			"hen", "her", "hew", "hey", "hi", "hid", "him", "hip", "his", "hit", "ho", "hob", "hoc", "hoe", "hog",
			"hop", "hot", "how", "hub", "hue", "hug", "huh", "hum", "hut", "i", "icy", "ida", "if", "ike", "ill", "ink",
			"inn", "io", "ion", "iq", "ira", "ire", "irk", "is", "it", "its", "ivy", "jab", "jag", "jam", "jan", "jar",
			"jaw", "jay", "jet", "jig", "jim", "jo", "job", "joe", "jog", "jot", "joy", "jug", "jut", "kay", "keg",
			"ken", "key", "kid", "kim", "kin", "kit", "la", "lab", "lac", "lad", "lag", "lam", "lap", "law", "lay",
			"lea", "led", "lee", "leg", "len", "leo", "let", "lew", "lid", "lie", "lin", "lip", "lit", "lo", "lob",
			"log", "lop", "los", "lot", "lou", "low", "loy", "lug", "lye", "ma", "mac", "mad", "mae", "man", "mao",
			"map", "mat", "maw", "may", "me", "meg", "mel", "men", "met", "mew", "mid", "min", "mit", "mob", "mod",
			"moe", "moo", "mop", "mos", "mot", "mow", "mud", "mug", "mum", "my", "nab", "nag", "nan", "nap", "nat",
			"nay", "ne", "ned", "nee", "net", "new", "nib", "nil", "nip", "nit", "no", "nob", "nod", "non", "nor",
			"not", "nov", "now", "nu", "nun", "nut", "o", "oaf", "oak", "oar", "oat", "odd", "ode", "of", "off", "oft",
			"oh", "oil", "ok", "old", "on", "one", "or", "orb", "ore", "orr", "os", "ott", "our", "out", "ova", "ow",
			"owe", "owl", "own", "ox", "pa", "pad", "pal", "pam", "pan", "pap", "par", "pat", "paw", "pay", "pea",
			"peg", "pen", "pep", "per", "pet", "pew", "phi", "pi", "pie", "pin", "pit", "ply", "po", "pod", "poe",
			"pop", "pot", "pow", "pro", "pry", "pub", "pug", "pun", "pup", "put", "quo", "rag", "ram", "ran", "rap",
			"rat", "raw", "ray", "reb", "red", "rep", "ret", "rib", "rid", "rig", "rim", "rio", "rip", "rob", "rod",
			"roe", "ron", "rot", "row", "roy", "rub", "rue", "rug", "rum", "run", "rye", "sac", "sad", "sag", "sal",
			"sam", "san", "sap", "sat", "saw", "say", "sea", "sec", "see", "sen", "set", "sew", "she", "shy", "sin",
			"sip", "sir", "sis", "sit", "ski", "sky", "sly", "so", "sob", "sod", "son", "sop", "sow", "soy", "spa",
			"spy", "sub", "sud", "sue", "sum", "sun", "sup", "tab", "tad", "tag", "tan", "tap", "tar", "tea", "ted",
			"tee", "ten", "the", "thy", "tic", "tie", "tim", "tin", "tip", "to", "toe", "tog", "tom", "ton", "too",
			"top", "tow", "toy", "try", "tub", "tug", "tum", "tun", "two", "un", "up", "us", "use", "van", "vat", "vet",
			"vie", "wad", "wag", "war", "was", "way", "we", "web", "wed", "wee", "wet", "who", "why", "win", "wit",
			"wok", "won", "woo", "wow", "wry", "wu", "yam", "yap", "yaw", "ye", "yea", "yes", "yet", "you", "abed",
			"abel", "abet", "able", "abut", "ache", "acid", "acme", "acre", "acta", "acts", "adam", "adds", "aden",
			"afar", "afro", "agee", "ahem", "ahoy", "aida", "aide", "aids", "airy", "ajar", "akin", "alan", "alec",
			"alga", "alia", "ally", "alma", "aloe", "also", "alto", "alum", "alva", "amen", "ames", "amid", "ammo",
			"amok", "amos", "amra", "andy", "anew", "anna", "anne", "ante", "anti", "aqua", "arab", "arch", "area",
			"argo", "arid", "army", "arts", "arty", "asia", "asks", "atom", "aunt", "aura", "auto", "aver", "avid",
			"avis", "avon", "avow", "away", "awry", "babe", "baby", "bach", "back", "bade", "bail", "bait", "bake",
			"bald", "bale", "bali", "balk", "ball", "balm", "band", "bane", "bang", "bank", "barb", "bard", "bare",
			"bark", "barn", "barr", "base", "bash", "bask", "bass", "bate", "bath", "bawd", "bawl", "bead", "beak",
			"beam", "bean", "bear", "beat", "beau", "beck", "beef", "been", "beer", "beet", "bela", "bell", "belt",
			"bend", "bent", "berg", "bern", "bert", "bess", "best", "beta", "beth", "bhoy", "bias", "bide", "bien",
			"bile", "bilk", "bill", "bind", "bing", "bird", "bite", "bits", "blab", "blat", "bled", "blew", "blob",
			"bloc", "blot", "blow", "blue", "blum", "blur", "boar", "boat", "boca", "bock", "bode", "body", "bogy",
			"bohr", "boil", "bold", "bolo", "bolt", "bomb", "bona", "bond", "bone", "bong", "bonn", "bony", "book",
			"boom", "boon", "boot", "bore", "borg", "born", "bose", "boss", "both", "bout", "bowl", "boyd", "brad",
			"brae", "brag", "bran", "bray", "bred", "brew", "brig", "brim", "brow", "buck", "budd", "buff", "bulb",
			"bulk", "bull", "bunk", "bunt", "buoy", "burg", "burl", "burn", "burr", "burt", "bury", "bush", "buss",
			"bust", "busy", "byte", "cady", "cafe", "cage", "cain", "cake", "calf", "call", "calm", "came", "cane",
			"cant", "card", "care", "carl", "carr", "cart", "case", "cash", "cask", "cast", "cave", "ceil", "cell",
			"cent", "cern", "chad", "char", "chat", "chaw", "chef", "chen", "chew", "chic", "chin", "chou", "chow",
			"chub", "chug", "chum", "cite", "city", "clad", "clam", "clan", "claw", "clay", "clod", "clog", "clot",
			"club", "clue", "coal", "coat", "coca", "cock", "coco", "coda", "code", "cody", "coed", "coil", "coin",
			"coke", "cola", "cold", "colt", "coma", "comb", "come", "cook", "cool", "coon", "coot", "cord", "core",
			"cork", "corn", "cost", "cove", "cowl", "crab", "crag", "cram", "cray", "crew", "crib", "crow", "crud",
			"cuba", "cube", "cuff", "cull", "cult", "cuny", "curb", "curd", "cure", "curl", "curt", "cuts", "dade",
			"dale", "dame", "dana", "dane", "dang", "dank", "dare", "dark", "darn", "dart", "dash", "data", "date",
			"dave", "davy", "dawn", "days", "dead", "deaf", "deal", "dean", "dear", "debt", "deck", "deed", "deem",
			"deer", "deft", "defy", "dell", "dent", "deny", "desk", "dial", "dice", "died", "diet", "dime", "dine",
			"ding", "dint", "dire", "dirt", "disc", "dish", "disk", "dive", "dock", "does", "dole", "doll", "dolt",
			"dome", "done", "doom", "door", "dora", "dose", "dote", "doug", "dour", "dove", "down", "drab", "drag",
			"dram", "draw", "drew", "drub", "drug", "drum", "dual", "duck", "duct", "duel", "duet", "duke", "dull",
			"dumb", "dune", "dunk", "dusk", "dust", "duty", "each", "earl", "earn", "ease", "east", "easy", "eben",
			"echo", "eddy", "eden", "edge", "edgy", "edit", "edna", "egan", "elan", "elba", "ella", "else", "emil",
			"emit", "emma", "ends", "eric", "eros", "even", "ever", "evil", "eyed", "face", "fact", "fade", "fail",
			"fain", "fair", "fake", "fall", "fame", "fang", "farm", "fast", "fate", "fawn", "fear", "feat", "feed",
			"feel", "feet", "fell", "felt", "fend", "fern", "fest", "feud", "fief", "figs", "file", "fill", "film",
			"find", "fine", "fink", "fire", "firm", "fish", "fisk", "fist", "fits", "five", "flag", "flak", "flam",
			"flat", "flaw", "flea", "fled", "flew", "flit", "floc", "flog", "flow", "flub", "flue", "foal", "foam",
			"fogy", "foil", "fold", "folk", "fond", "font", "food", "fool", "foot", "ford", "fore", "fork", "form",
			"fort", "foss", "foul", "four", "fowl", "frau", "fray", "fred", "free", "fret", "frey", "frog", "from",
			"fuel", "full", "fume", "fund", "funk", "fury", "fuse", "fuss", "gaff", "gage", "gail", "gain", "gait",
			"gala", "gale", "gall", "galt", "game", "gang", "garb", "gary", "gash", "gate", "gaul", "gaur", "gave",
			"gawk", "gear", "geld", "gene", "gent", "germ", "gets", "gibe", "gift", "gild", "gill", "gilt", "gina",
			"gird", "girl", "gist", "give", "glad", "glee", "glen", "glib", "glob", "glom", "glow", "glue", "glum",
			"glut", "goad", "goal", "goat", "goer", "goes", "gold", "golf", "gone", "gong", "good", "goof", "gore",
			"gory", "gosh", "gout", "gown", "grab", "grad", "gray", "greg", "grew", "grey", "grid", "grim", "grin",
			"grit", "grow", "grub", "gulf", "gull", "gunk", "guru", "gush", "gust", "gwen", "gwyn", "haag", "haas",
			"hack", "hail", "hair", "hale", "half", "hall", "halo", "halt", "hand", "hang", "hank", "hans", "hard",
			"hark", "harm", "hart", "hash", "hast", "hate", "hath", "haul", "have", "hawk", "hays", "head", "heal",
			"hear", "heat", "hebe", "heck", "heed", "heel", "heft", "held", "hell", "helm", "herb", "herd", "here",
			"hero", "hers", "hess", "hewn", "hick", "hide", "high", "hike", "hill", "hilt", "hind", "hint", "hire",
			"hiss", "hive", "hobo", "hock", "hoff", "hold", "hole", "holm", "holt", "home", "hone", "honk", "hood",
			"hoof", "hook", "hoot", "horn", "hose", "host", "hour", "hove", "howe", "howl", "hoyt", "huck", "hued",
			"huff", "huge", "hugh", "hugo", "hulk", "hull", "hunk", "hunt", "hurd", "hurl", "hurt", "hush", "hyde",
			"hymn", "ibis", "icon", "idea", "idle", "iffy", "inca", "inch", "into", "ions", "iota", "iowa", "iris",
			"irma", "iron", "isle", "itch", "item", "ivan", "jack", "jade", "jail", "jake", "jane", "java", "jean",
			"jeff", "jerk", "jess", "jest", "jibe", "jill", "jilt", "jive", "joan", "jobs", "jock", "joel", "joey",
			"john", "join", "joke", "jolt", "jove", "judd", "jude", "judo", "judy", "juju", "juke", "july", "june",
			"junk", "juno", "jury", "just", "jute", "kahn", "kale", "kane", "kant", "karl", "kate", "keel", "keen",
			"keno", "kent", "kern", "kerr", "keys", "kick", "kill", "kind", "king", "kirk", "kiss", "kite", "klan",
			"knee", "knew", "knit", "knob", "knot", "know", "koch", "kong", "kudo", "kurd", "kurt", "kyle", "lace",
			"lack", "lacy", "lady", "laid", "lain", "lair", "lake", "lamb", "lame", "land", "lane", "lang", "lard",
			"lark", "lass", "last", "late", "laud", "lava", "lawn", "laws", "lays", "lead", "leaf", "leak", "lean",
			"lear", "leek", "leer", "left", "lend", "lens", "lent", "leon", "lesk", "less", "lest", "lets", "liar",
			"lice", "lick", "lied", "lien", "lies", "lieu", "life", "lift", "like", "lila", "lilt", "lily", "lima",
			"limb", "lime", "lind", "line", "link", "lint", "lion", "lisa", "list", "live", "load", "loaf", "loam",
			"loan", "lock", "loft", "loge", "lois", "lola", "lone", "long", "look", "loon", "loot", "lord", "lore",
			"lose", "loss", "lost", "loud", "love", "lowe", "luck", "lucy", "luge", "luke", "lulu", "lund", "lung",
			"lura", "lure", "lurk", "lush", "lust", "lyle", "lynn", "lyon", "lyra", "mace", "made", "magi", "maid",
			"mail", "main", "make", "male", "mali", "mall", "malt", "mana", "mann", "many", "marc", "mare", "mark",
			"mars", "mart", "mary", "mash", "mask", "mass", "mast", "mate", "math", "maul", "mayo", "mead", "meal",
			"mean", "meat", "meek", "meet", "meld", "melt", "memo", "mend", "menu", "mert", "mesh", "mess", "mice",
			"mike", "mild", "mile", "milk", "mill", "milt", "mimi", "mind", "mine", "mini", "mink", "mint", "mire",
			"miss", "mist", "mite", "mitt", "moan", "moat", "mock", "mode", "mold", "mole", "moll", "molt", "mona",
			"monk", "mont", "mood", "moon", "moor", "moot", "more", "morn", "mort", "moss", "most", "moth", "move",
			"much", "muck", "mudd", "muff", "mule", "mull", "murk", "mush", "must", "mute", "mutt", "myra", "myth",
			"nagy", "nail", "nair", "name", "nary", "nash", "nave", "navy", "neal", "near", "neat", "neck", "need",
			"neil", "nell", "neon", "nero", "ness", "nest", "news", "newt", "nibs", "nice", "nick", "nile", "nina",
			"nine", "noah", "node", "noel", "noll", "none", "nook", "noon", "norm", "nose", "note", "noun", "nova",
			"nude", "null", "numb", "oath", "obey", "oboe", "odin", "ohio", "oily", "oint", "okay", "olaf", "oldy",
			"olga", "olin", "oman", "omen", "omit", "once", "ones", "only", "onto", "onus", "oral", "orgy", "oslo",
			"otis", "otto", "ouch", "oust", "outs", "oval", "oven", "over", "owly", "owns", "quad", "quit", "quod",
			"race", "rack", "racy", "raft", "rage", "raid", "rail", "rain", "rake", "rank", "rant", "rare", "rash",
			"rate", "rave", "rays", "read", "real", "ream", "rear", "reck", "reed", "reef", "reek", "reel", "reid",
			"rein", "rena", "rend", "rent", "rest", "rice", "rich", "rick", "ride", "rift", "rill", "rime", "ring",
			"rink", "rise", "risk", "rite", "road", "roam", "roar", "robe", "rock", "rode", "roil", "roll", "rome",
			"rood", "roof", "rook", "room", "root", "rosa", "rose", "ross", "rosy", "roth", "rout", "rove", "rowe",
			"rows", "rube", "ruby", "rude", "rudy", "ruin", "rule", "rung", "runs", "runt", "ruse", "rush", "rusk",
			"russ", "rust", "ruth", "sack", "safe", "sage", "said", "sail", "sale", "salk", "salt", "same", "sand",
			"sane", "sang", "sank", "sara", "saul", "save", "says", "scan", "scar", "scat", "scot", "seal", "seam",
			"sear", "seat", "seed", "seek", "seem", "seen", "sees", "self", "sell", "send", "sent", "sets", "sewn",
			"shag", "sham", "shaw", "shay", "shed", "shim", "shin", "shod", "shoe", "shot", "show", "shun", "shut",
			"sick", "side", "sift", "sigh", "sign", "silk", "sill", "silo", "silt", "sine", "sing", "sink", "sire",
			"site", "sits", "situ", "skat", "skew", "skid", "skim", "skin", "skit", "slab", "slam", "slat", "slay",
			"sled", "slew", "slid", "slim", "slit", "slob", "slog", "slot", "slow", "slug", "slum", "slur", "smog",
			"smug", "snag", "snob", "snow", "snub", "snug", "soak", "soar", "sock", "soda", "sofa", "soft", "soil",
			"sold", "some", "song", "soon", "soot", "sore", "sort", "soul", "sour", "sown", "stab", "stag", "stan",
			"star", "stay", "stem", "stew", "stir", "stow", "stub", "stun", "such", "suds", "suit", "sulk", "sums",
			"sung", "sunk", "sure", "surf", "swab", "swag", "swam", "swan", "swat", "sway", "swim", "swum", "tack",
			"tact", "tail", "take", "tale", "talk", "tall", "tank", "task", "tate", "taut", "teal", "team", "tear",
			"tech", "teem", "teen", "teet", "tell", "tend", "tent", "term", "tern", "tess", "test", "than", "that",
			"thee", "them", "then", "they", "thin", "this", "thud", "thug", "tick", "tide", "tidy", "tied", "tier",
			"tile", "till", "tilt", "time", "tina", "tine", "tint", "tiny", "tire", "toad", "togo", "toil", "told",
			"toll", "tone", "tong", "tony", "took", "tool", "toot", "tore", "torn", "tote", "tour", "tout", "town",
			"trag", "tram", "tray", "tree", "trek", "trig", "trim", "trio", "trod", "trot", "troy", "true", "tuba",
			"tube", "tuck", "tuft", "tuna", "tune", "tung", "turf", "turn", "tusk", "twig", "twin", "twit", "ulan",
			"unit", "urge", "used", "user", "uses", "utah", "vail", "vain", "vale", "vary", "vase", "vast", "veal",
			"veda", "veil", "vein", "vend", "vent", "verb", "very", "veto", "vice", "view", "vine", "vise", "void",
			"volt", "vote", "wack", "wade", "wage", "wail", "wait", "wake", "wale", "walk", "wall", "walt", "wand",
			"wane", "wang", "want", "ward", "warm", "warn", "wart", "wash", "wast", "wats", "watt", "wave", "wavy",
			"ways", "weak", "weal", "wean", "wear", "weed", "week", "weir", "weld", "well", "welt", "went", "were",
			"wert", "west", "wham", "what", "whee", "when", "whet", "whoa", "whom", "wick", "wife", "wild", "will",
			"wind", "wine", "wing", "wink", "wino", "wire", "wise", "wish", "with", "wolf", "wont", "wood", "wool",
			"word", "wore", "work", "worm", "worn", "wove", "writ", "wynn", "yale", "yang", "yank", "yard", "yarn",
			"yawl", "yawn", "yeah", "year", "yell", "yoga", "yoke" };

	private static final HashMap<String, Integer> CODES = buildCodes();

	/**
	 * Length of each mnemonic word in bits
	 */
	private static final int WL=11;
	
	/**
	 * Encode bytes as a mnemonic string
	 * 
	 * @param data Byte array to encode
	 * @return Mnemonic String
	 */
	public static String encode(byte[] data) {
		int bitLength = data.length * 8;
		int n = (bitLength + WL-1) / WL;
		String[] words = new String[n];
		for (int i = 0; i < n; i++) {
			// extract WL bits for each word
			int position = bitLength-(i+1)*WL;
			int bits = 0x7FF & Utils.extractBits(data, WL, position);
			words[i] = WORDS[bits];
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < n; i++) {
			if (i > 0) sb.append(' ');
			sb.append(words[i]);
		}
		return sb.toString();
	}

	/**
	 * Encode bytes as a mnemonic string
	 * 
	 * @param x Bytes to encode
	 * @param bitLength Length of key to encode
	 * @return Mnemonic String
	 */
	public static String encode(BigInteger x, int bitLength) {
		return Mnemonic.encode(Utils.hexToBytes(Utils.toHexString(x, bitLength >> 2)));
	}

	private static HashMap<String, Integer> buildCodes() {
		HashMap<String, Integer> hm = new HashMap<>(WORDS.length); // big enough to avoid resize
		for (int i = 0; i < WORDS.length; i++) {
			hm.put(WORDS[i], i);
		}
		return hm;
	}

	/**
	 * Decode from a Mnemonic string
	 * @param phrase Mnemonic string
	 * @param bitLength Bits to extract
	 * @return Decoded byte array
	 */
	public static byte[] decode(String phrase, int bitLength) {
		int nByte = (bitLength + 7) / 8; // number of bytes required
		byte[] result = new byte[nByte];

		phrase = phrase.trim().toLowerCase();
		String[] words = phrase.split("\\s+");
		int n = words.length;
		if (n * WL < bitLength)
			throw new IllegalArgumentException("Insufficient words (" + n + ") to cover bitlength of " + bitLength);

		for (int i = 0; i < n; i++) {
			String word = words[i].trim();
			Integer x = CODES.get(word);
			if (x == null) throw new IllegalArgumentException(
					"Can't find word (" + word + ") in mnemonic dictionary for phrase " + phrase);
			int position = bitLength-(i+1)*WL;
			Utils.setBits(result, WL, position, x);
		}

		return result;
	}

	/**
	 * Create a secure random mnemonic string
	 * 
	 * @return Mnemonic String
	 */
	public static String createSecureRandom() {
		byte[] bs = Blob.createRandom(new SecureRandom(), 16).getBytes();
		return encode(bs);
	}

	/**
	 * Create a keypair from a mnemonic string
	 * @param mnemonic RFC1751 mnemonic string
	 * @return Key pair instance
	 */
	@SuppressWarnings("unchecked")
	public static <T extends AKeyPair> T decodeKeyPair(String mnemonic) {
		return (T) decodeKeyPair(mnemonic, null);
	}
	
	/**
	 * Create a keypair from a mnemonic string
	 * @param mnemonic RFC1751 mnemonic string
	 * @param passphrase Additional passphrase for specific key (may be null / empty)
	 * @return Key pair instance
	 */
	@SuppressWarnings("unchecked")
	public static <T extends AKeyPair> T decodeKeyPair(String mnemonic, String passphrase) {
		byte[] bs = Mnemonic.decode(mnemonic, 128);
		BlobBuilder bb=new BlobBuilder();
		bb.append(bs);
		if (passphrase!=null) {
			bb.append(passphrase.getBytes(StandardCharsets.UTF_8));
		}
		ABlob b=bb.toBlob();
		Hash h = b.getContentHash();
		Ed25519KeyPair kp = Ed25519KeyPair.create(h.getBytes());
		return (T) kp;
	}
}
