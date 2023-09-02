package convex.restapi.api;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import convex.api.Convex;
import convex.core.Result;
import convex.core.crypto.ASignature;
import convex.core.crypto.Ed25519Signature;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.data.Blobs;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.Lists;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.MissingDataException;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.lang.Symbols;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
import convex.core.util.Utils;
import convex.java.JSON;
import convex.restapi.RESTServer;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.InternalServerErrorResponse;
import io.javalin.http.ServiceUnavailableResponse;
import io.javalin.openapi.*;

public class ChainAPI extends ABaseAPI {

	public Convex convex;
	
	public ChainAPI(RESTServer restServer) {
		super(restServer);
		convex=restServer.getConvex();
	}
	
    private static final String ROUTE = "/api/v1/";


	@Override
	public void addRoutes(Javalin app) {
		String prefix=ROUTE;
		
		app.post(prefix+"createAccount", this::createAccount);
		app.post(prefix+"query", this::runQuery);
		
		app.post(prefix+"faucet", this::faucetRequest);
		
		app.post(prefix+"transaction/prepare", this::runTransactionPrepare);
		app.post(prefix+"transaction/submit", this::runTransactionSubmit);
 
		app.get(prefix+"accounts/<addr>", this::queryAccount);
		
		app.get(prefix+"data/<hash>", this::getData);
	}
	
	@OpenApi(path = ROUTE+"data/{hash}",
			methods = HttpMethod.POST,
	        operationId = "data",
	        pathParams = {
	          @OpenApiParam(
	        		  name = "hash", 
	        		  description = "Data hash as a hex string. Leading '0x' is optional but discouraged.", 
	        		  required = true, 
	        		  type = String.class,
	        		  example = "0x1234567812345678123456781234567812345678123456781234567812345678")
	        })
	public void getData(Context ctx) {
		String hashParam=ctx.pathParam("hash");
		Hash h=Hash.parse(hashParam);
		if (h==null) {
			throw new BadRequestResponse(jsonError("Invalid hash: "+hashParam));
		}
		
		ACell d;
		try {
			d=convex.acquire(h).get(1000, TimeUnit.MILLISECONDS);
		} catch (ExecutionException e) {
			throw new BadRequestResponse(jsonError("Missing Data: "+e.getMessage()));
		} catch (Exception e) {
			throw new BadRequestResponse(jsonError("Error: "+e.getMessage()));
		}
		String ds=Utils.print(d);
		ctx.result(ds);
	}

	public void createAccount(Context ctx) {
		Map<String, Object> req=getJSONBody(ctx);
		Object key = req.get("accountKey");
		if (key == null)
			throw new BadRequestResponse(jsonError("Expected JSON body containing 'accountKey' field"));

		AccountKey pk = AccountKey.parse(key);
		if (pk == null)
			throw new BadRequestResponse(jsonError("Unable to parse accountKey: " + key));

		Address a;
		try {
			a = convex.createAccountSync(pk);
		} catch (TimeoutException e) {
			throw new ServiceUnavailableResponse(jsonError("Timeout in request"));
		} catch (IOException e) {
			throw new InternalServerErrorResponse(jsonError(e.getMessage()));
		}
		ctx.result("{\"address\": " + a.longValue() + "}");
	}

	public void queryAccount(Context ctx) {
		Address addr=null;
		String addrParam=ctx.pathParam("addr");
		try {
			long a=Long.parseLong(addrParam);
			addr=Address.create(a);
			if (addr==null) throw new BadRequestResponse(jsonError("Invalid address: "+a));
		} catch(Exception e) {
			throw new BadRequestResponse(jsonError("Expected valid account number in path but got ["+addrParam+"]"));
		}
		
		Result r= doQuery(Lists.of(Symbols.ACCOUNT,addr));
		
		if (r.isError()) {
			ctx.json(jsonForErrorResult(r));
			return;
		}
		
		AccountStatus as=r.getValue();
		if (as==null) {
			ctx.result("{\"errorCode\": \"NOBODY\", \"source\": \"Server\",\"value\": \"The Account requested does not exist.\"}");
			ctx.status(404);
			return;
		}
		
		boolean isUser=!as.isActor();
		// TODO: consider if isLibrary is useful?
		// boolean isLibrary=as.getCallableFunctions().isEmpty();
		
		HashMap<String,Object> hm=new HashMap<>();
		hm.put("address",addr.toExactLong());
		hm.put("allowance",as.getMemory());
		hm.put("balance",as.getBalance());
		hm.put("memorySize",as.getMemorySize());
		hm.put("sequence",as.getSequence());
		hm.put("type", isUser?"user":"actor");
		
		ctx.result(JSON.toPrettyString(hm));
	}
	
	/**
	 * Runs a query, wrapping exceptions
	 * @param form
	 * @return
	 */
	private Result doQuery(ACell form) {
		try {
			return convex.querySync(form);
		} catch (TimeoutException e) {
			throw new ServiceUnavailableResponse(jsonError("Timeout in query request"));
		} catch (IOException e) {
			throw new InternalServerErrorResponse(jsonError("IOException in query request: "+e));
		} catch (Exception e) {
			throw new InternalServerErrorResponse(jsonError("Failed to execute query: "+e));
		}
	}
	
	/**
	 * Runs a transaction, wrapping exceptions
	 * @param form
	 * @return
	 */
	private Result doTransaction(SignedData<ATransaction> signedTransaction) {
		try {
			return convex.transactSync(signedTransaction);
		} catch (TimeoutException e) {
			throw new ServiceUnavailableResponse(jsonError("Timeout executing transaction - unable to confirm result."));
		} catch (IOException e) {
			throw new InternalServerErrorResponse(jsonError("IOException in request: "+e));
		} catch (Exception e) {
			throw new InternalServerErrorResponse(jsonError("Failed to execute transaction: "+e));
		}
	}
	
	private HashMap<String,Object> jsonResult(Result r) {
		if (r.isError()) return jsonForErrorResult(r);
		
		HashMap<String,Object> hm=new HashMap<>();
		hm.put("value", RT.json(r.getValue()));
		return hm;
	}
	
	private HashMap<String,Object> jsonForErrorResult(Result r) {
		HashMap<String,Object> hm=new HashMap<>();
		hm.put("errorCode", RT.name(r.getErrorCode()).toString());
		hm.put("source", "Server");
		hm.put("value", RT.json(r.getValue()));
		return hm;
	}

	public void faucetRequest(Context ctx) {
		Map<String, Object> req=getJSONBody(ctx);
		Address addr=Address.parse(req.get("address")); 
		if (addr == null)
			throw new BadRequestResponse(jsonError("Expected JSON body containing 'address' field"));

		Object o=req.get("amount");
		CVMLong l=CVMLong.parse(o);
		if (l==null)  throw new BadRequestResponse(jsonError("faucet requires an 'amount' field containing a long value."));

		try {
			// SECURITY: Make sure this is not subject to injection attack
			// Optional: pre-compile to Op
			Result r=convex.transactSync("(transfer "+addr+" "+l+")");
			if (r.isError()) {
				HashMap<String,Object> hm=jsonForErrorResult(r);
				ctx.json(hm);
			} else {
				req.put("amount", r.getValue());
				ctx.result(JSON.toPrettyString(req));
			}
		} catch (TimeoutException e) {
			throw new ServiceUnavailableResponse(jsonError("Timeout in request"));
		} catch (IOException e) {
			throw new InternalServerErrorResponse(jsonError(e.getMessage()));
		}
		
	}
	
	public void runTransactionPrepare(Context ctx) {
		Map<String, Object> req=getJSONBody(ctx);
		Address addr=Address.parse(req.get("address")); 
		if (addr==null) throw new BadRequestResponse(jsonError("Transaction prepare requires an 'address' field."));
		Object srcValue=req.get("source");
		if (!(srcValue instanceof String)) throw new BadRequestResponse(jsonError("Source code required for query (as a string)"));
	
		ACell code=null;
		try {
			code=Reader.read((String)srcValue);
		} catch (Exception e) {
			throw new BadRequestResponse(jsonError("Source code did not compile: "+e.getMessage()));
		}
		
		try {
			long sequence=convex.getSequence(addr);
			long nextSeq=sequence+1;
			ATransaction trans=Invoke.create(addr, nextSeq, code);
			Ref<ATransaction> ref=ACell.createPersisted(trans);
			
			HashMap<String,Object> rmap=new HashMap<>();
			rmap.put("source",srcValue);
			rmap.put("address", RT.json(addr));
			rmap.put("hash", RT.json(ref.getHash()));
			rmap.put("sequence", sequence);
	
			ctx.result(JSON.toPrettyString(rmap));
		} catch (Exception e) {
			throw new InternalServerErrorResponse(jsonError("Error preparing transaction: "+e.getMessage()));
		}
	}
	
	public void runTransactionSubmit(Context ctx) {
		Map<String, Object> req=getJSONBody(ctx);
		Address addr=Address.parse(req.get("address")); 
		if (addr==null) throw new BadRequestResponse(jsonError("query requires an 'address' field."));
		
		// Get the transaction hash
		Object hashValue=req.get("hash");
		if (!(hashValue instanceof String)) throw new BadRequestResponse(jsonError("Parameter 'hash' must be provided as a String"));
		Hash h=Hash.parse(hashValue);
		if (h==null) throw new BadRequestResponse(jsonError("Parameter 'hash' did not parse correctly, must be 64 hex characters."));

		ATransaction trans=null;
		try {
			ACell maybeTrans=Ref.forHash(h).getValue();
			if (!(maybeTrans instanceof ATransaction)) throw new BadRequestResponse(jsonError("Value with hash "+h+" is not a transaction: can't submit it!"));
			trans=(ATransaction)maybeTrans;
		} catch (MissingDataException e) {
			throw new BadRequestResponse(jsonError("Could not find transaction with hash "+h+": probably you need to call 'prepare' first?"));
		} catch (Exception e) {
			throw new BadRequestResponse(jsonError("Failed to identify transaction with hash "+h+": "+e.getMessage()));
		}
		
		// Get the account key
		Object keyValue = req.get("accountKey");
		if (!(keyValue instanceof String)) throw new BadRequestResponse(jsonError("Expected JSON body containing 'accountKey' field"));
		AccountKey key=AccountKey.parse(keyValue);
		if (key==null) throw new BadRequestResponse(jsonError("Parameter 'accountKey' did not parse correctly, must be 64 hex characters."));

		
		// Get the signature
		Object sigValue=req.get("sig");
		if (!(sigValue instanceof String)) throw new BadRequestResponse(jsonError("Parameter 'sig' must be provided as a String"));
		ABlob sigData=Blobs.parse(sigValue);
		if ((sigData==null)||(sigData.count()!=Ed25519Signature.SIGNATURE_LENGTH)) {
			throw new BadRequestResponse(jsonError("Parameter 'sig' must be a 64 byte hex String"));
		}
		ASignature sig=Ed25519Signature.fromBlob(sigData);
				
		SignedData<ATransaction> sd=SignedData.create(key, sig, trans.getRef());
		Result r=doTransaction(sd);
		HashMap<String,Object> rm=jsonResult(r);
		if (rm==null) throw new InternalServerErrorResponse(jsonError("Couldn't parse Result: "+r));
		
		ctx.result(JSON.toPrettyString(rm));
	}
	
	
	@OpenApi(path = "/query",
			methods = HttpMethod.POST
	)
	public void runQuery(Context ctx) {
		Map<String, Object> req=getJSONBody(ctx);
		Address addr=Address.parse(req.get("address")); 
		if (addr==null) throw new BadRequestResponse(jsonError("query requires an 'address' field."));
		Object srcValue=req.get("source");
		if (!(srcValue instanceof String)) throw new BadRequestResponse(jsonError("Source code required for query (as a string)"));
		
		Object cvxRaw=req.get("raw");
		
		String src=(String)srcValue;
		ACell form=Reader.read(src);
		try {
			Result r=convex.querySync(form,addr);
			
			HashMap<String,Object> rmap=new HashMap<>();
			Object jsonValue;
			if (cvxRaw==null) {
				jsonValue=RT.json(r.getValue());
			} else {
				jsonValue=RT.toString(r.getValue());
			}
			
			rmap.put("value", jsonValue);
			ACell ecode=r.getErrorCode();
			if (ecode instanceof Keyword) {
				rmap.put("errorCode", ((Keyword)ecode).getName().toString());
			}
			
			ctx.result(JSON.toString(rmap));
		} catch (TimeoutException e) {
			throw new ServiceUnavailableResponse(jsonError("Timeout in request"));
		} catch (IOException e) {
			throw new InternalServerErrorResponse(jsonError(e.getMessage()));
		}
	}


}
