package lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Address;
import convex.core.cvm.Context;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.ASet;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.lang.ACVMTest;
import convex.core.lang.TestState;
import convex.core.util.Utils;
import convex.lib.AssetTester;

public class ArchonTest extends ACVMTest  {

	Address ARCHON;
	
	@Override protected Context buildContext(Context ctx) {
		ctx=TestState.CONTEXT.fork();
		
		ctx=exec(ctx,"(import convex.asset :as asset)");
		ctx=exec(ctx,"(import convex.trust :as trust)");
		
		// User accounts for testing
		ctx=exec(ctx,"(def HERO *address*)");
		ctx=exec(ctx,"(def VILLAIN "+VILLAIN+")");
		
		ctx=exec(ctx,"(def archon (deploy '(set-controller *caller*)))");
		ARCHON=ctx.getResult();
		
		try {
			String code=Utils.readResourceAsString("/convex/lab/archon.cvx");
			ctx=exec(ctx,"(eval-as archon '(do "+code+"))");
			
			
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			throw new Error(e);
		}
		return ctx;
	}
	
	@Test public void testArchonActor() {
		Context ctx=context();
		Address archon=eval(ctx,"archon");
		assertEquals(ARCHON,archon);
		
		assertEquals(1024,evalL(ctx,"(count (asset/balance archon))"));
		
		// Metadata should work and have an image
		AHashMap<AString,ACell> meta=eval(ctx,"(call archon (get-metadata 0x0123))");
		assertFalse(meta.isEmpty());
		assertTrue(meta.containsKey(Strings.create("image")));
		
		assertNull(eval(ctx,"(call archon (get-metadata :not-a-valid-id))"));
	}
	
	@Test public void testArchonNFTs() {
		Context ctx=context();
		ctx=exec(ctx,"(asset/transfer "+VILLAIN+" [archon #{0x0123}])");
		AssetTester.doAssetTests(ctx, ARCHON, HERO, VILLAIN);
	}
	
	@Test public void testPeaceSetup() {
		Context ctx=context();
		
		String patch="(let [vs [:I :believe :that :unarmed :truth :and :unconditional :love :will \r\n"
		+ "		:have  :the :final :word :in :reality :This :is :why :right :temporarily \r\n"
		+ "		:defeated :is-1 :stronger :than :evil :triumphant :I-1 :believe-1 :that-1 :even \r\n"
		+ "		:amid :todays :mortar :bursts :and-1 :whining :bullets :there :is-2 :still :hope \r\n"
		+ "		:for :a :brighter :tomorrow :I-2 :believe-2 :that-2 :wounded :justice :lying\r\n"
		+ "		:prostrate :on :the-1 :blood-flowing :streets :of :our :nations :can :be :lifted \r\n"
		+ "		:from :this :dust :of :shame :to :reign :supreme :among :the-2 :children :of :men \r\n"
		+ "		:I-3 :have :the-3 :audacity :to :believe-3 :that-3 :peoples :everywhere :can :have\r\n"
		+ "		:three :meals :a :day :for-1 :their :bodies :education :and-2 :culture :for-2 \r\n"
		+ "		:their-1 :minds :dignity :bar1 :bar2 :bar3 :bar4 :bar5 :bar6 :bar7]\r\n"
		+ "      vset (into #{} vs)]\r\n"
		+ "     vset)";
		
		ctx=exec(ctx,patch);
		assertTrue(ctx.getResult() instanceof ASet);
	}
}
