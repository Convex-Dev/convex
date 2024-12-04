package convex.core.crypto;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.bouncycastle.util.Arrays;

import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.util.Utils;
import convex.core.exceptions.Panic;

public class BIP39 {
	static final String[] wordlist = { "abandon", "ability", "able", "about", "above", "absent", "absorb",
			"abstract", "absurd", "abuse", "access", "accident", "account", "accuse", "achieve", "acid", "acoustic",
			"acquire", "across", "act", "action", "actor", "actress", "actual", "adapt", "add", "addict", "address",
			"adjust", "admit", "adult", "advance", "advice", "aerobic", "affair", "afford", "afraid", "again", "age",
			"agent", "agree", "ahead", "aim", "air", "airport", "aisle", "alarm", "album", "alcohol", "alert", "alien",
			"all", "alley", "allow", "almost", "alone", "alpha", "already", "also", "alter", "always", "amateur",
			"amazing", "among", "amount", "amused", "analyst", "anchor", "ancient", "anger", "angle", "angry", "animal",
			"ankle", "announce", "annual", "another", "answer", "antenna", "antique", "anxiety", "any", "apart",
			"apology", "appear", "apple", "approve", "april", "arch", "arctic", "area", "arena", "argue", "arm",
			"armed", "armor", "army", "around", "arrange", "arrest", "arrive", "arrow", "art", "artefact", "artist",
			"artwork", "ask", "aspect", "assault", "asset", "assist", "assume", "asthma", "athlete", "atom", "attack",
			"attend", "attitude", "attract", "auction", "audit", "august", "aunt", "author", "auto", "autumn",
			"average", "avocado", "avoid", "awake", "aware", "away", "awesome", "awful", "awkward", "axis", "baby",
			"bachelor", "bacon", "badge", "bag", "balance", "balcony", "ball", "bamboo", "banana", "banner", "bar",
			"barely", "bargain", "barrel", "base", "basic", "basket", "battle", "beach", "bean", "beauty", "because",
			"become", "beef", "before", "begin", "behave", "behind", "believe", "below", "belt", "bench", "benefit",
			"best", "betray", "better", "between", "beyond", "bicycle", "bid", "bike", "bind", "biology", "bird",
			"birth", "bitter", "black", "blade", "blame", "blanket", "blast", "bleak", "bless", "blind", "blood",
			"blossom", "blouse", "blue", "blur", "blush", "board", "boat", "body", "boil", "bomb", "bone", "bonus",
			"book", "boost", "border", "boring", "borrow", "boss", "bottom", "bounce", "box", "boy", "bracket", "brain",
			"brand", "brass", "brave", "bread", "breeze", "brick", "bridge", "brief", "bright", "bring", "brisk",
			"broccoli", "broken", "bronze", "broom", "brother", "brown", "brush", "bubble", "buddy", "budget",
			"buffalo", "build", "bulb", "bulk", "bullet", "bundle", "bunker", "burden", "burger", "burst", "bus",
			"business", "busy", "butter", "buyer", "buzz", "cabbage", "cabin", "cable", "cactus", "cage", "cake",
			"call", "calm", "camera", "camp", "can", "canal", "cancel", "candy", "cannon", "canoe", "canvas", "canyon",
			"capable", "capital", "captain", "car", "carbon", "card", "cargo", "carpet", "carry", "cart", "case",
			"cash", "casino", "castle", "casual", "cat", "catalog", "catch", "category", "cattle", "caught", "cause",
			"caution", "cave", "ceiling", "celery", "cement", "census", "century", "cereal", "certain", "chair",
			"chalk", "champion", "change", "chaos", "chapter", "charge", "chase", "chat", "cheap", "check", "cheese",
			"chef", "cherry", "chest", "chicken", "chief", "child", "chimney", "choice", "choose", "chronic", "chuckle",
			"chunk", "churn", "cigar", "cinnamon", "circle", "citizen", "city", "civil", "claim", "clap", "clarify",
			"claw", "clay", "clean", "clerk", "clever", "click", "client", "cliff", "climb", "clinic", "clip", "clock",
			"clog", "close", "cloth", "cloud", "clown", "club", "clump", "cluster", "clutch", "coach", "coast",
			"coconut", "code", "coffee", "coil", "coin", "collect", "color", "column", "combine", "come", "comfort",
			"comic", "common", "company", "concert", "conduct", "confirm", "congress", "connect", "consider", "control",
			"convince", "cook", "cool", "copper", "copy", "coral", "core", "corn", "correct", "cost", "cotton", "couch",
			"country", "couple", "course", "cousin", "cover", "coyote", "crack", "cradle", "craft", "cram", "crane",
			"crash", "crater", "crawl", "crazy", "cream", "credit", "creek", "crew", "cricket", "crime", "crisp",
			"critic", "crop", "cross", "crouch", "crowd", "crucial", "cruel", "cruise", "crumble", "crunch", "crush",
			"cry", "crystal", "cube", "culture", "cup", "cupboard", "curious", "current", "curtain", "curve", "cushion",
			"custom", "cute", "cycle", "dad", "damage", "damp", "dance", "danger", "daring", "dash", "daughter", "dawn",
			"day", "deal", "debate", "debris", "decade", "december", "decide", "decline", "decorate", "decrease",
			"deer", "defense", "define", "defy", "degree", "delay", "deliver", "demand", "demise", "denial", "dentist",
			"deny", "depart", "depend", "deposit", "depth", "deputy", "derive", "describe", "desert", "design", "desk",
			"despair", "destroy", "detail", "detect", "develop", "device", "devote", "diagram", "dial", "diamond",
			"diary", "dice", "diesel", "diet", "differ", "digital", "dignity", "dilemma", "dinner", "dinosaur",
			"direct", "dirt", "disagree", "discover", "disease", "dish", "dismiss", "disorder", "display", "distance",
			"divert", "divide", "divorce", "dizzy", "doctor", "document", "dog", "doll", "dolphin", "domain", "donate",
			"donkey", "donor", "door", "dose", "double", "dove", "draft", "dragon", "drama", "drastic", "draw", "dream",
			"dress", "drift", "drill", "drink", "drip", "drive", "drop", "drum", "dry", "duck", "dumb", "dune",
			"during", "dust", "dutch", "duty", "dwarf", "dynamic", "eager", "eagle", "early", "earn", "earth", "easily",
			"east", "easy", "echo", "ecology", "economy", "edge", "edit", "educate", "effort", "egg", "eight", "either",
			"elbow", "elder", "electric", "elegant", "element", "elephant", "elevator", "elite", "else", "embark",
			"embody", "embrace", "emerge", "emotion", "employ", "empower", "empty", "enable", "enact", "end", "endless",
			"endorse", "enemy", "energy", "enforce", "engage", "engine", "enhance", "enjoy", "enlist", "enough",
			"enrich", "enroll", "ensure", "enter", "entire", "entry", "envelope", "episode", "equal", "equip", "era",
			"erase", "erode", "erosion", "error", "erupt", "escape", "essay", "essence", "estate", "eternal", "ethics",
			"evidence", "evil", "evoke", "evolve", "exact", "example", "excess", "exchange", "excite", "exclude",
			"excuse", "execute", "exercise", "exhaust", "exhibit", "exile", "exist", "exit", "exotic", "expand",
			"expect", "expire", "explain", "expose", "express", "extend", "extra", "eye", "eyebrow", "fabric", "face",
			"faculty", "fade", "faint", "faith", "fall", "false", "fame", "family", "famous", "fan", "fancy", "fantasy",
			"farm", "fashion", "fat", "fatal", "father", "fatigue", "fault", "favorite", "feature", "february",
			"federal", "fee", "feed", "feel", "female", "fence", "festival", "fetch", "fever", "few", "fiber",
			"fiction", "field", "figure", "file", "film", "filter", "final", "find", "fine", "finger", "finish", "fire",
			"firm", "first", "fiscal", "fish", "fit", "fitness", "fix", "flag", "flame", "flash", "flat", "flavor",
			"flee", "flight", "flip", "float", "flock", "floor", "flower", "fluid", "flush", "fly", "foam", "focus",
			"fog", "foil", "fold", "follow", "food", "foot", "force", "forest", "forget", "fork", "fortune", "forum",
			"forward", "fossil", "foster", "found", "fox", "fragile", "frame", "frequent", "fresh", "friend", "fringe",
			"frog", "front", "frost", "frown", "frozen", "fruit", "fuel", "fun", "funny", "furnace", "fury", "future",
			"gadget", "gain", "galaxy", "gallery", "game", "gap", "garage", "garbage", "garden", "garlic", "garment",
			"gas", "gasp", "gate", "gather", "gauge", "gaze", "general", "genius", "genre", "gentle", "genuine",
			"gesture", "ghost", "giant", "gift", "giggle", "ginger", "giraffe", "girl", "give", "glad", "glance",
			"glare", "glass", "glide", "glimpse", "globe", "gloom", "glory", "glove", "glow", "glue", "goat", "goddess",
			"gold", "good", "goose", "gorilla", "gospel", "gossip", "govern", "gown", "grab", "grace", "grain", "grant",
			"grape", "grass", "gravity", "great", "green", "grid", "grief", "grit", "grocery", "group", "grow", "grunt",
			"guard", "guess", "guide", "guilt", "guitar", "gun", "gym", "habit", "hair", "half", "hammer", "hamster",
			"hand", "happy", "harbor", "hard", "harsh", "harvest", "hat", "have", "hawk", "hazard", "head", "health",
			"heart", "heavy", "hedgehog", "height", "hello", "helmet", "help", "hen", "hero", "hidden", "high", "hill",
			"hint", "hip", "hire", "history", "hobby", "hockey", "hold", "hole", "holiday", "hollow", "home", "honey",
			"hood", "hope", "horn", "horror", "horse", "hospital", "host", "hotel", "hour", "hover", "hub", "huge",
			"human", "humble", "humor", "hundred", "hungry", "hunt", "hurdle", "hurry", "hurt", "husband", "hybrid",
			"ice", "icon", "idea", "identify", "idle", "ignore", "ill", "illegal", "illness", "image", "imitate",
			"immense", "immune", "impact", "impose", "improve", "impulse", "inch", "include", "income", "increase",
			"index", "indicate", "indoor", "industry", "infant", "inflict", "inform", "inhale", "inherit", "initial",
			"inject", "injury", "inmate", "inner", "innocent", "input", "inquiry", "insane", "insect", "inside",
			"inspire", "install", "intact", "interest", "into", "invest", "invite", "involve", "iron", "island",
			"isolate", "issue", "item", "ivory", "jacket", "jaguar", "jar", "jazz", "jealous", "jeans", "jelly",
			"jewel", "job", "join", "joke", "journey", "joy", "judge", "juice", "jump", "jungle", "junior", "junk",
			"just", "kangaroo", "keen", "keep", "ketchup", "key", "kick", "kid", "kidney", "kind", "kingdom", "kiss",
			"kit", "kitchen", "kite", "kitten", "kiwi", "knee", "knife", "knock", "know", "lab", "label", "labor",
			"ladder", "lady", "lake", "lamp", "language", "laptop", "large", "later", "latin", "laugh", "laundry",
			"lava", "law", "lawn", "lawsuit", "layer", "lazy", "leader", "leaf", "learn", "leave", "lecture", "left",
			"leg", "legal", "legend", "leisure", "lemon", "lend", "length", "lens", "leopard", "lesson", "letter",
			"level", "liar", "liberty", "library", "license", "life", "lift", "light", "like", "limb", "limit", "link",
			"lion", "liquid", "list", "little", "live", "lizard", "load", "loan", "lobster", "local", "lock", "logic",
			"lonely", "long", "loop", "lottery", "loud", "lounge", "love", "loyal", "lucky", "luggage", "lumber",
			"lunar", "lunch", "luxury", "lyrics", "machine", "mad", "magic", "magnet", "maid", "mail", "main", "major",
			"make", "mammal", "man", "manage", "mandate", "mango", "mansion", "manual", "maple", "marble", "march",
			"margin", "marine", "market", "marriage", "mask", "mass", "master", "match", "material", "math", "matrix",
			"matter", "maximum", "maze", "meadow", "mean", "measure", "meat", "mechanic", "medal", "media", "melody",
			"melt", "member", "memory", "mention", "menu", "mercy", "merge", "merit", "merry", "mesh", "message",
			"metal", "method", "middle", "midnight", "milk", "million", "mimic", "mind", "minimum", "minor", "minute",
			"miracle", "mirror", "misery", "miss", "mistake", "mix", "mixed", "mixture", "mobile", "model", "modify",
			"mom", "moment", "monitor", "monkey", "monster", "month", "moon", "moral", "more", "morning", "mosquito",
			"mother", "motion", "motor", "mountain", "mouse", "move", "movie", "much", "muffin", "mule", "multiply",
			"muscle", "museum", "mushroom", "music", "must", "mutual", "myself", "mystery", "myth", "naive", "name",
			"napkin", "narrow", "nasty", "nation", "nature", "near", "neck", "need", "negative", "neglect", "neither",
			"nephew", "nerve", "nest", "net", "network", "neutral", "never", "news", "next", "nice", "night", "noble",
			"noise", "nominee", "noodle", "normal", "north", "nose", "notable", "note", "nothing", "notice", "novel",
			"now", "nuclear", "number", "nurse", "nut", "oak", "obey", "object", "oblige", "obscure", "observe",
			"obtain", "obvious", "occur", "ocean", "october", "odor", "off", "offer", "office", "often", "oil", "okay",
			"old", "olive", "olympic", "omit", "once", "one", "onion", "online", "only", "open", "opera", "opinion",
			"oppose", "option", "orange", "orbit", "orchard", "order", "ordinary", "organ", "orient", "original",
			"orphan", "ostrich", "other", "outdoor", "outer", "output", "outside", "oval", "oven", "over", "own",
			"owner", "oxygen", "oyster", "ozone", "pact", "paddle", "page", "pair", "palace", "palm", "panda", "panel",
			"panic", "panther", "paper", "parade", "parent", "park", "parrot", "party", "pass", "patch", "path",
			"patient", "patrol", "pattern", "pause", "pave", "payment", "peace", "peanut", "pear", "peasant", "pelican",
			"pen", "penalty", "pencil", "people", "pepper", "perfect", "permit", "person", "pet", "phone", "photo",
			"phrase", "physical", "piano", "picnic", "picture", "piece", "pig", "pigeon", "pill", "pilot", "pink",
			"pioneer", "pipe", "pistol", "pitch", "pizza", "place", "planet", "plastic", "plate", "play", "please",
			"pledge", "pluck", "plug", "plunge", "poem", "poet", "point", "polar", "pole", "police", "pond", "pony",
			"pool", "popular", "portion", "position", "possible", "post", "potato", "pottery", "poverty", "powder",
			"power", "practice", "praise", "predict", "prefer", "prepare", "present", "pretty", "prevent", "price",
			"pride", "primary", "print", "priority", "prison", "private", "prize", "problem", "process", "produce",
			"profit", "program", "project", "promote", "proof", "property", "prosper", "protect", "proud", "provide",
			"public", "pudding", "pull", "pulp", "pulse", "pumpkin", "punch", "pupil", "puppy", "purchase", "purity",
			"purpose", "purse", "push", "put", "puzzle", "pyramid", "quality", "quantum", "quarter", "question",
			"quick", "quit", "quiz", "quote", "rabbit", "raccoon", "race", "rack", "radar", "radio", "rail", "rain",
			"raise", "rally", "ramp", "ranch", "random", "range", "rapid", "rare", "rate", "rather", "raven", "raw",
			"razor", "ready", "real", "reason", "rebel", "rebuild", "recall", "receive", "recipe", "record", "recycle",
			"reduce", "reflect", "reform", "refuse", "region", "regret", "regular", "reject", "relax", "release",
			"relief", "rely", "remain", "remember", "remind", "remove", "render", "renew", "rent", "reopen", "repair",
			"repeat", "replace", "report", "require", "rescue", "resemble", "resist", "resource", "response", "result",
			"retire", "retreat", "return", "reunion", "reveal", "review", "reward", "rhythm", "rib", "ribbon", "rice",
			"rich", "ride", "ridge", "rifle", "right", "rigid", "ring", "riot", "ripple", "risk", "ritual", "rival",
			"river", "road", "roast", "robot", "robust", "rocket", "romance", "roof", "rookie", "room", "rose",
			"rotate", "rough", "round", "route", "royal", "rubber", "rude", "rug", "rule", "run", "runway", "rural",
			"sad", "saddle", "sadness", "safe", "sail", "salad", "salmon", "salon", "salt", "salute", "same", "sample",
			"sand", "satisfy", "satoshi", "sauce", "sausage", "save", "say", "scale", "scan", "scare", "scatter",
			"scene", "scheme", "school", "science", "scissors", "scorpion", "scout", "scrap", "screen", "script",
			"scrub", "sea", "search", "season", "seat", "second", "secret", "section", "security", "seed", "seek",
			"segment", "select", "sell", "seminar", "senior", "sense", "sentence", "series", "service", "session",
			"settle", "setup", "seven", "shadow", "shaft", "shallow", "share", "shed", "shell", "sheriff", "shield",
			"shift", "shine", "ship", "shiver", "shock", "shoe", "shoot", "shop", "short", "shoulder", "shove",
			"shrimp", "shrug", "shuffle", "shy", "sibling", "sick", "side", "siege", "sight", "sign", "silent", "silk",
			"silly", "silver", "similar", "simple", "since", "sing", "siren", "sister", "situate", "six", "size",
			"skate", "sketch", "ski", "skill", "skin", "skirt", "skull", "slab", "slam", "sleep", "slender", "slice",
			"slide", "slight", "slim", "slogan", "slot", "slow", "slush", "small", "smart", "smile", "smoke", "smooth",
			"snack", "snake", "snap", "sniff", "snow", "soap", "soccer", "social", "sock", "soda", "soft", "solar",
			"soldier", "solid", "solution", "solve", "someone", "song", "soon", "sorry", "sort", "soul", "sound",
			"soup", "source", "south", "space", "spare", "spatial", "spawn", "speak", "special", "speed", "spell",
			"spend", "sphere", "spice", "spider", "spike", "spin", "spirit", "split", "spoil", "sponsor", "spoon",
			"sport", "spot", "spray", "spread", "spring", "spy", "square", "squeeze", "squirrel", "stable", "stadium",
			"staff", "stage", "stairs", "stamp", "stand", "start", "state", "stay", "steak", "steel", "stem", "step",
			"stereo", "stick", "still", "sting", "stock", "stomach", "stone", "stool", "story", "stove", "strategy",
			"street", "strike", "strong", "struggle", "student", "stuff", "stumble", "style", "subject", "submit",
			"subway", "success", "such", "sudden", "suffer", "sugar", "suggest", "suit", "summer", "sun", "sunny",
			"sunset", "super", "supply", "supreme", "sure", "surface", "surge", "surprise", "surround", "survey",
			"suspect", "sustain", "swallow", "swamp", "swap", "swarm", "swear", "sweet", "swift", "swim", "swing",
			"switch", "sword", "symbol", "symptom", "syrup", "system", "table", "tackle", "tag", "tail", "talent",
			"talk", "tank", "tape", "target", "task", "taste", "tattoo", "taxi", "teach", "team", "tell", "ten",
			"tenant", "tennis", "tent", "term", "test", "text", "thank", "that", "theme", "then", "theory", "there",
			"they", "thing", "this", "thought", "three", "thrive", "throw", "thumb", "thunder", "ticket", "tide",
			"tiger", "tilt", "timber", "time", "tiny", "tip", "tired", "tissue", "title", "toast", "tobacco", "today",
			"toddler", "toe", "together", "toilet", "token", "tomato", "tomorrow", "tone", "tongue", "tonight", "tool",
			"tooth", "top", "topic", "topple", "torch", "tornado", "tortoise", "toss", "total", "tourist", "toward",
			"tower", "town", "toy", "track", "trade", "traffic", "tragic", "train", "transfer", "trap", "trash",
			"travel", "tray", "treat", "tree", "trend", "trial", "tribe", "trick", "trigger", "trim", "trip", "trophy",
			"trouble", "truck", "true", "truly", "trumpet", "trust", "truth", "try", "tube", "tuition", "tumble",
			"tuna", "tunnel", "turkey", "turn", "turtle", "twelve", "twenty", "twice", "twin", "twist", "two", "type",
			"typical", "ugly", "umbrella", "unable", "unaware", "uncle", "uncover", "under", "undo", "unfair", "unfold",
			"unhappy", "uniform", "unique", "unit", "universe", "unknown", "unlock", "until", "unusual", "unveil",
			"update", "upgrade", "uphold", "upon", "upper", "upset", "urban", "urge", "usage", "use", "used", "useful",
			"useless", "usual", "utility", "vacant", "vacuum", "vague", "valid", "valley", "valve", "van", "vanish",
			"vapor", "various", "vast", "vault", "vehicle", "velvet", "vendor", "venture", "venue", "verb", "verify",
			"version", "very", "vessel", "veteran", "viable", "vibrant", "vicious", "victory", "video", "view",
			"village", "vintage", "violin", "virtual", "virus", "visa", "visit", "visual", "vital", "vivid", "vocal",
			"voice", "void", "volcano", "volume", "vote", "voyage", "wage", "wagon", "wait", "walk", "wall", "walnut",
			"want", "warfare", "warm", "warrior", "wash", "wasp", "waste", "water", "wave", "way", "wealth", "weapon",
			"wear", "weasel", "weather", "web", "wedding", "weekend", "weird", "welcome", "west", "wet", "whale",
			"what", "wheat", "wheel", "when", "where", "whip", "whisper", "wide", "width", "wife", "wild", "will",
			"win", "window", "wine", "wing", "wink", "winner", "winter", "wire", "wisdom", "wise", "wish", "witness",
			"wolf", "woman", "wonder", "wood", "wool", "word", "work", "world", "worry", "worth", "wrap", "wreck",
			"wrestle", "wrist", "write", "wrong", "yard", "year", "yellow", "you", "young", "youth", "zebra", "zero",
			"zone", "zoo"};
	
	public static final int NUM_WORDS=wordlist.length;
	
	public static final int BITS_PER_WORD=11;
	
	public static final String DEMO_PHRASE="sing bomb stay manual powder hard north mixture sausage lunch retreat desert";
	public static final String DEMO_PASS="hello1234567890ZZ";
	
	/**
	 * Map of words to integer values
	 */
	private static final HashMap<String,Integer> LOOKUP=new HashMap<>();
	
	/**
	 * Map of abbreviated words to full words
	 */
	private static final HashMap<String,String> ABBR=new HashMap<>();

	public static final int SEED_LENGTH = 64;
	
	/**
	 * Minimum number of words accepted in a mnemonic
	 */
	public static final int MIN_WORDS=3;
	
	static {
		for (int i=0; i<NUM_WORDS; i++) {
			String word=wordlist[i];
			LOOKUP.put(word, i);
			
			String abbr=word.substring(0, Math.min(4, word.length()));
			ABBR.put(abbr, word);
		}
	}
	
	/**
	 * Gets a BIP39 seed given a mnemonic and passphrase
	 * @param words Mnemonic words
	 * @param passphrase Optional BIP39 passphrase
	 * @return Blob containing BIP39 seed (64 bytes)
	 */
	public static Blob getSeed(List<String> words, String passphrase) throws NoSuchAlgorithmException, InvalidKeySpecException {
		return getSeed(mnemonic(words),passphrase);
	}
	
	public static AKeyPair seedToKeyPair(Blob seed) {
		Blob edSeed=seedToEd25519Seed(seed);
		return AKeyPair.create(edSeed);
	}

	/**
	 * Converts a BIP39 seed to an Ed25519 seed. This is done by taking the first 32 bytes of the SLIP-10 master key
	 * 
	 * Note: longer term users may want hierarchical deterministic wallet generation
	 * 
	 * @param seed BIP39 seed
	 * @return Ed25519 seed as a Blob
	 */
	public static Blob seedToEd25519Seed(Blob seed) {
		long n=seed.count();
		if (n!=SEED_LENGTH) {
			throw new IllegalArgumentException("Expected "+SEED_LENGTH+ " byte BIP39 seed but was: "+n);
		}
		Blob master=SLIP10.getMaster(seed);
		return master.slice(0, 32);
	}
	
	/**
	 * Tests if the string is a valid BIP39 mnemonic phrase, returns null if no problem
	 * @param s String to be tested as a mnemonic phrase
	 * @return String containing reason that mnemonic is not valid, or null if OK
	 */
	public static String checkMnemonic(String s) {
		List<String> words=getWords(s);
		if (words.size()<MIN_WORDS) return "Inadqeuate number of words in BIP39 mnemonic (at least "+MIN_WORDS+" recommended)";
		
		String err= checkWords(words);
		if (err!=null) return "Not in word list: "+err;
		
		if (!s.equals(normaliseFormat(s))) return "String not normalised";
		
		if (!checkSum(s)) return "Invalid checksum";
		
		return null;
	}
	

	
	/**
	 * Gets a BIP39 seed given a mnemonic and passphrase
	 * @param mnemonic Mnemonic words
	 * @param passphrase Optional BIP39 passphrase
	 * @return Blob containing BIP39 seed (64 bytes)
	 */
	public static Blob getSeed(String mnemonic, String passphrase) {
			mnemonic=Normalizer.normalize(mnemonic, Normalizer.Form.NFKD);		
			char[] normalisedMnemonic= mnemonic.toCharArray(); 
			return getSeedInternal(normalisedMnemonic,passphrase);
	}
	
	private static Blob getSeedInternal(char[] normalisedMnemonic, String passphrase)  {
		try {
			// Normalise passphrase and convert to byte array
			passphrase=Normalizer.normalize(passphrase, Normalizer.Form.NFKD);	
			byte[] salt = ("mnemonic"+passphrase).getBytes(StandardCharsets.UTF_8);
			
			// Generate seed
			SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512");
			KeySpec keyspec = new PBEKeySpec(normalisedMnemonic, salt, 2048, SEED_LENGTH * 8);
		    Key key = factory.generateSecret(keyspec);
		    
		    // Wrap result as Blob
		    byte[] bs = key.getEncoded();
		    return Blob.wrap(bs);
		} catch (NoSuchAlgorithmException| InvalidKeySpecException e) {
			throw new Panic("Security error getting BIP39 seed",e);
		}
	}
	
	/**
	 * Creates a normalised BIP39 mnemonic with the specified number of words
	 * @param numWords Number of words to generate
	 * @return String containing normalised BIP39 mnemonic
	 */
	public static String createSecureMnemonic(int numWords) {
		return Utils.joinStrings(createWords(new SecureRandom(),numWords)," ");
	}

	/**
	 * Create a list of random mnemonic words given a random number generator
	 * @param r Random number generator instance
	 * @param n Number of words to generate
	 * @return List of words
	 */
	public static List<String> createWords(SecureRandom r, int n) {
		int CS=n/3; // number of checksum bits
		int ENT=CS*32;

		byte[] bs=new byte[ENT/8]; // enough space for entropy
		r.nextBytes(bs);
		
		return createWordsAddingChecksum(bs,n);
	}
	
	public static List<String> createWordsAddingChecksum(byte[] entropy, int n) {
		int CS=n/3; // number of checksum bits
		int ENT=CS*32;
		Hash checkHash=Hashing.sha256(entropy);
		int checkSum=Utils.extractBits(checkHash.getBytes(), CS, 256-CS); // BIP39 checksum
		
		int blen=((CS+ENT+7)/8); // enough space for entropy plus checksum
		byte[] bs=new byte[blen];
		System.arraycopy(entropy, 0, bs, 0, ENT/8);
		Utils.setBits(bs, CS, (blen*8)-(ENT+CS),checkSum);
		
		return createWords(bs,n);
	}
	
	/**
	 * Check if BIP39 checksum is correct
	 * @param s
	 * @return True if BIP39 checksum is valid
	 */
	public static boolean checkSum(String mnemonic) {
		List<String> words=getWords(mnemonic);
		int n=words.size();
		byte[] bs=mnemonicToBytes(words);
		if (bs==null) return false;
		
		int CS=n/3; // number of checksum bits
		int ENT=CS*32;
		Hash checkHash=Hashing.sha256(Arrays.copyOf(bs, ENT/8));
		int checkSum=Utils.extractBits(checkHash.getBytes(), CS, 256-CS); // BIP39 checksum
		
		int storedSum=Utils.extractBits(bs, CS, (bs.length*8)-(ENT+CS));
		return checkSum==storedSum;	
	}
	
	public static List<String> createWords(byte[] material, int n) {
		int mbits=material.length*8;
		ArrayList<String> al=new ArrayList<>(n);
		for (int i=0; i<n; i++) {
			int ix=Utils.extractBits(material, BITS_PER_WORD, mbits - (i+1)*BITS_PER_WORD);
			String word=wordlist[ix];
			al.add(word);
		}
		return al;
	}
	
	/**
	 * Gets bytes containing the entropy and checksum used to create the given words
	 * @param mnemonic
	 * @return byte array of sufficient size, or null if not valid BIP39 words
	 */
	public static byte[] mnemonicToBytes(String mnemonic) {
		List<String> words=getWords(mnemonic);
		return mnemonicToBytes(words);
	}
	
	/**
	 * Gets bytes containing the entropy and checksum used to create the given words
	 * @param mnemonic
	 * @return byte array of sufficient size, or null if not valid BIP39 words
	 */
	public static byte[] mnemonicToBytes(List<String> words) {
		int n=words.size();
		if ((n<MIN_WORDS)||(n>24)) return null;
		
		int CS=n/3;
		if ((CS*3!=n)) return null; // must be a multiple of 3 for valid BIP39
		int ENT=CS*32;
		
		int blen=((CS+ENT+7)/8); // enough space for entropy plus checksum
		byte[] bs=new byte[blen];
		
		for (int i=0; i<n; i++) {
			String w=words.get(i);
			Integer ix=LOOKUP.get(w);
			if (ix==null) return null;
			Utils.setBits(bs, BITS_PER_WORD, (blen*8) - (i+1)*BITS_PER_WORD, ix);
		}
		return bs;
	}

	/**
	 * Gets the individual words from a mnemonic String. Will trim and normalise whitespace
	 * @param mnemonic Mnemonic String
	 * @return List of words
	 */
	public static List<String> getWords(String mnemonic) {
		mnemonic=mnemonic.trim();
		mnemonic=normaliseFormat(mnemonic);
		String[] ss=mnemonic.split(" ");
		ArrayList<String> al=new ArrayList<>();
		for (int i=0; i<ss.length; i++) {
			String w=ss[i].trim();
			if (!w.isBlank()) {
				al.add(w);
			}
		}
		return al;
	}

	public static String normaliseFormat(String s) {
		s=s.trim().replaceAll("\\s+"," ");
		s=s.toLowerCase();
		return s;
	}
	
	public static String normaliseAll(String s) {
		// to lowercase and standard whitespace
		s=normaliseFormat(s);
		
		List<String> words=getWords(s);
		
		int n=words.size();
		for (int i=0; i<n; i++) {
			String w=words.get(i);
			if (LOOKUP.containsKey(w)) continue; // legit word, continue
			
			String ext=extendWord(w);
			if (ext!=null) {
				words.set(i, ext);
				continue;
			}
			
			words.set(i, w.toUpperCase()); // An unexpected word, highlight in uppercase
		}
		
		String result = mnemonic(words);
		return result;
	}

	/**
	 * Create a mnemonic String from a list of words, separated by spaces
	 * @param words List of words for mnemonic
	 * @return Combined mnemonic string
	 */
	public static String mnemonic(List<String> words) {
		StringBuilder sb=new StringBuilder();
		for (String w: words) {
			boolean start=sb.isEmpty();
			if (!start) sb.append(' ');
			sb.append(w);
		}
		return sb.toString();
	}

	/**
	 * Check a list of words, returns the first word not in word list
	 * @param words List of words to check
	 * @return First incorrect word, or null if all OK
	 */
	public static String checkWords(List<String> words) {
		for (String w: words) {
			if (!LOOKUP.containsKey(w)) return w;
		}
		return null;
	}

	/**
	 * Extends an abbreviated form of a BIP39 word to a full word e.g. 'SHAL' => 'shallow'
	 * @param abbr
	 * @return
	 */
	public static String extendWord(String abbr) {
		return ABBR.get(abbr.trim().toLowerCase());
	}

	public static int[] parsePath(String path) {
		try {
			String[] es=path.split("/");
			if (!"m".equals(es[0])) throw new Exception("<Bad derivation path, must start with 'm'>");
			
			int n=es.length-1;
			int[] proposedPath=new int[n];
			for (int i=0; i<n; i++) {
				try {
					Integer ix= Integer.parseInt(es[i+1]);
					proposedPath[i]=ix;
				} catch (NumberFormatException e) {
					throw new Exception("<Bad derivation path, should be integer indexes 'm/44/888/1/0/123' >");
				}
			}
			return proposedPath;
		} catch (Exception ex) {
			return null;
		}
	}
	
}
