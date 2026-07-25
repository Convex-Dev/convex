package convex.restapi.api;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.message.MessageType;

class GenericMessagePolicyTest {

	@Test
	void everyProtocolTypeIsClassifiedAndCurrentlyPermitted() {
		for (MessageType type:MessageType.values()) {
			assertNotNull(GenericMessagePolicy.classify(type),type::name);
			assertTrue(GenericMessagePolicy.allows(type),type::name);
		}
	}
}
