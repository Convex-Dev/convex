package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.Constants;
import convex.core.cvm.AOp;
import convex.core.cvm.Address;
import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Call;
import convex.core.cvm.transactions.Invoke;
import convex.core.cvm.transactions.Multi;
import convex.core.cvm.transactions.Transfer;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Symbol;

public class TransactionGen extends Generator<ATransaction> {
	public TransactionGen() {
		super(ATransaction.class);
	}

	@SuppressWarnings("unchecked")
	@Override
	public ATransaction generate(SourceOfRandomness r, GenerationStatus status) {
		int size=status.size();
		
		Address origin = Address.create(r.nextInt(1+size));
		Address dst = Address.create(r.nextInt(1+size));
		long seq = r.nextInt(1+size);
		
		switch (r.nextInt(10)) {
		case 0: {
			long amt = r.nextLong(0, Constants.MAX_SUPPLY);
			return Transfer.create(origin,seq, dst, amt);
		}

		case 1: {
			Symbol sym=Gen.SYMBOL.generate(r, status);
			AVector<ACell> args=Gen.VECTOR.generate(r, status);
			return Call.create(origin,seq,dst,sym, args);
		}
		
		case 2: {
			ATransaction[] txs=new ATransaction[r.nextInt(0, 3)];
			for (int i=0; i<txs.length; i++) {
				txs[i]=generate(r,status);
			}
			int mode=r.nextInt(Multi.MODE_ANY, Multi.MODE_UNTIL);
			return Multi.create(origin, seq, mode, txs);
		}
		
		case 3: 
			AOp<?> op=Gen.OP.generate(r, status);
			return Invoke.create(origin,seq,op);

		default:
			ACell form=Gen.FORM.generate(r, status);
			return Invoke.create(origin,seq, form);
		}
	}
}
