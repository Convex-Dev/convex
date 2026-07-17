package convex.restapi.api;

import java.util.LinkedHashMap;
import java.util.Objects;

import convex.core.Result;
import convex.core.ResultContext;
import convex.core.SourceCodes;
import convex.core.cvm.Address;
import convex.core.cvm.Peer;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;

/** Stateful evaluation and encoding for one watched query. */
final class QueryWatch {

	static final long MAX_QUERY_JUICE=100_000;

	private static final Keyword K_POSITION=Keyword.intern("position");
	private static final Keyword K_RESULT=Keyword.intern("result");

	record Event(long position, Result result) {}

	private final ACell form;
	private final Address address;
	private Hash lastHash;

	QueryWatch(ACell form, Address address) {
		this.form=form;
		this.address=address;
	}

	Event evaluate(Peer peer) {
		Result result;
		try {
			ResultContext context=peer.executeQuery(form,address,MAX_QUERY_JUICE);
			result=Result.fromContext(null,context).withSource(SourceCodes.PEER);
		} catch (Exception e) {
			result=Result.fromException(e).withSource(SourceCodes.PEER);
		}

		Hash hash=result.getHash();
		if (Cells.equals(lastHash,hash)) return null;
		lastHash=hash;
		return new Event(peer.getStatePosition(),result);
	}

	static String encode(Event event, WatchFormat format, int maxChars) {
		if (maxChars<=0) throw new IllegalArgumentException("Maximum event size must be positive");
		Objects.requireNonNull(format,"Watch format cannot be null");
		Result result=event.result();

		// Establish a bounded traversal before JSON conversion, whose legacy utility
		// representation does not itself expose a print limit.
		AString boundedResult=RT.print(result,maxChars);
		if (boundedResult==null) return null;

		if (format==WatchFormat.CVX) {
			AMap<ACell,ACell> envelope=Maps.of(
				K_POSITION,CVMLong.create(event.position()),
				K_RESULT,result);
			AString output=RT.print(envelope,maxChars);
			return (output==null)?null:output.toString();
		}

		LinkedHashMap<String,Object> envelope=new LinkedHashMap<>();
		envelope.put("position",event.position());
		envelope.put("result",result.toJSON());
		String output=JSON.print(envelope).toString();
		return (output.length()<=maxChars)?output:null;
	}
}
