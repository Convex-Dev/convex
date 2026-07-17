package convex.restapi.api;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import convex.core.cvm.Address;
import convex.core.cvm.Log;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AVector;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.restapi.api.ConsensusLogScanner.LogEvent;

/** Pure filtering and encoding support for consensus log watches. */
final class LogWatch {

	private static final Keyword K_BLOCK=Keyword.intern("block");
	private static final Keyword K_TRANSACTION=Keyword.intern("transaction");
	private static final Keyword K_LOG=Keyword.intern("log");
	private static final Keyword K_ENTRY=Keyword.intern("entry");

	record Filter(Set<Address> addresses, Set<ACell> events, Set<ACell> scopes) {
		Filter {
			if ((addresses==null)||addresses.isEmpty()) {
				throw new IllegalArgumentException("At least one address is required");
			}
			addresses=Collections.unmodifiableSet(new HashSet<>(addresses));
			events=immutableNullableSet(events);
			scopes=immutableNullableSet(scopes);
		}

		private static <T> Set<T> immutableNullableSet(Set<T> values) {
			if ((values==null)||values.isEmpty()) return Set.of();
			return Collections.unmodifiableSet(new HashSet<>(values));
		}

		boolean matches(LogEvent event) {
			AVector<ACell> entry=event.entry();
			if ((entry==null)||(entry.count()!=Log.ENTRY_LENGTH)) return false;
			if (!(entry.get(Log.P_ADDRESS) instanceof Address address)||!addresses.contains(address)) return false;
			if (!scopes.isEmpty()&&!matchesAny(scopes,entry.get(Log.P_SCOPE))) return false;
			if (events.isEmpty()) return true;
			if (!(entry.get(Log.P_VALUES) instanceof AVector<?> values)||(values.count()==0)) return false;
			return matchesAny(events,values.get(0));
		}

		private static boolean matchesAny(Set<ACell> filters, ACell value) {
			// Filter counts are small and bounded. Linear equality avoids asking a
			// potentially lazy log value to calculate a structural hash.
			for (ACell filter:filters) {
				if (Objects.equals(filter,value)) return true;
			}
			return false;
		}
	}

	static String eventID(LogEvent event) {
		return event.blockIndex()+":"+event.transactionIndex()+":"+event.logIndex();
	}

	static String encode(LogEvent event, WatchFormat format) {
		AMap<ACell,ACell> envelope=Maps.of(
			K_BLOCK,CVMLong.create(event.blockIndex()),
			K_TRANSACTION,CVMLong.create(event.transactionIndex()),
			K_LOG,CVMLong.create(event.logIndex()),
			K_ENTRY,event.entry());
		return switch (Objects.requireNonNull(format)) {
			case JSON -> JSON.print(envelope).toString();
			case CVX -> RT.print(envelope).toString();
		};
	}

	private LogWatch() {}
}
