package convex.restapi.api;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import convex.api.Convex;
import convex.core.Coin;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.crypto.Ed25519Signature;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Cells;
import convex.core.data.Format;
import convex.core.data.Hash;
import convex.core.data.Lists;
import convex.core.data.PeerStatus;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.data.prim.AInteger;
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
import convex.restapi.model.CVMResult;
import convex.restapi.model.CreateAccountRequest;
import convex.restapi.model.CreateAccountResponse;
import convex.restapi.model.QueryAccountResponse;
import convex.restapi.model.QueryRequest;
import convex.restapi.model.TransactRequest;
import convex.restapi.model.TransactionPrepareRequest;
import convex.restapi.model.TransactionPrepareResponse;
import convex.restapi.model.TransactionSubmitRequest;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.InternalServerErrorResponse;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.ServiceUnavailableResponse;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiExampleProperty;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;

public class ChainAPI extends ABaseAPI {

	public Convex convex;

	public ChainAPI(RESTServer restServer) {
		super(restServer);
		convex = restServer.getConvex();
	}

	private static final String ROUTE = "/api/v1/";

	@Override
	public void addRoutes(Javalin app) {
		String prefix = ROUTE;

		app.post(prefix + "createAccount", this::createAccount);
		app.post(prefix + "query", this::runQuery);

		app.post(prefix + "faucet", this::faucetRequest);

		app.post(prefix + "transaction/prepare", this::runTransactionPrepare);
		app.post(prefix + "transaction/submit", this::runTransactionSubmit);

		app.post(prefix + "transact", this::runTransact);

		app.get(prefix + "accounts/<addr>", this::queryAccount);
		app.get(prefix + "peers/<addr>", this::queryPeer);

		app.get(prefix + "data/<hash>", this::getData);
	}

	@OpenApi(path = ROUTE + "data/{hash}", 
			methods = HttpMethod.POST, 
			tags = { "Data Lattice"},
			summary = "Get data from the server with the specified hash", 
			operationId = "data", 
			pathParams = {
					@OpenApiParam(name = "hash", description = "Data hash as a hex string. Leading '0x' is optional but discouraged.", required = true, type = String.class, example = "0x1234567812345678123456781234567812345678123456781234567812345678") })
	public void getData(Context ctx) {
		String hashParam = ctx.pathParam("hash");
		Hash h = Hash.parse(hashParam);
		if (h == null) {
			throw new BadRequestResponse(jsonError("Invalid hash: " + hashParam));
		}

		ACell d;
		try {
			d = convex.acquire(h).get(1000, TimeUnit.MILLISECONDS);
		} catch (ExecutionException e) {
			throw new BadRequestResponse(jsonError("Missing Data: " + e.getMessage()));
		} catch (Exception e) {
			throw new BadRequestResponse(jsonError("Error: " + e.getMessage()));
		}
		String ds = Utils.print(d);
		ctx.result(ds);
	}

	@OpenApi(path = ROUTE + "createAccount", 
			methods = HttpMethod.POST, 
			operationId = "createAccount", 
			tags = { "Account"},
			summary = "Create a new Convex account", 
			requestBody = @OpenApiRequestBody(
				description = "Create Account request, must provide an accountKey for the new Account", 
				content = {@OpenApiContent(
								from = CreateAccountRequest.class, 
								type = "application/json", 
								exampleObjects = {
										@OpenApiExampleProperty(name = "accountKey", value = "d82e78594610f708ad47f666bbacbab1711760652cb88bf7515ed6c3ae84a08d") })}
			), 
			responses = {
				@OpenApiResponse(
						status = "200", 
						description = "Account created sucecssfully", 
						content = {
							@OpenApiContent(
									type = "application/json", 
									from = CreateAccountResponse.class) })
				})
	public void createAccount(Context ctx) {
		Map<String, Object> req = getJSONBody(ctx);
		Object key = req.get("accountKey");
		if (key == null)
			throw new BadRequestResponse(jsonError("Expected JSON body containing 'accountKey' field"));

		AccountKey pk = AccountKey.parse(key);
		if (pk == null)
			throw new BadRequestResponse(jsonError("Unable to parse accountKey: " + key));

		Object faucet = req.get("faucet");
		AInteger amt = AInteger.parse(faucet);

		Address a;
		try {
			a = convex.createAccountSync(pk);
			if (amt != null) {
				convex.transferSync(a, amt.longValue());
			}
		} catch (TimeoutException e) {
			throw new ServiceUnavailableResponse(jsonError("Timeout in request"));
		} catch (IOException e) {
			throw new InternalServerErrorResponse(jsonError(e.getMessage()));
		}
		ctx.result("{\"address\": " + a.longValue() + "}");
	}

	@OpenApi(path = ROUTE + "accounts/{address}", 
			methods = HttpMethod.GET, 
			operationId = "queryAccount", 
			tags = { "Account"},
			summary = "Get Convex account information", 
			pathParams = {
				 @OpenApiParam(name = "address", description = "Address of Account", required = true, type = String.class, example="14")
			},
			responses = {
				@OpenApiResponse(status = "200", 
						description = "Account queried sucecssfully", 
						content = {
							@OpenApiContent(
									from=QueryAccountResponse.class,
									type = "application/json") }),
				@OpenApiResponse(status = "404", 
						description = "Account does not exist" )
			}
		)
	public void queryAccount(Context ctx) {
		Address addr = null;
		String addrParam = ctx.pathParam("addr");

		addr = Address.parse(addrParam);
		if (addr == null) {
			throw new BadRequestResponse(jsonError("Invalid address: " + addrParam));
		}

		Result r = doQuery(Lists.of(Symbols.ACCOUNT, addr));

		if (r.isError()) {
			ctx.json(jsonResult(r));
			return;
		}

		AccountStatus as = r.getValue();
		if (as == null) {
			ctx.result(
					"{\"errorCode\": \"NOBODY\", \"source\": \"Server\",\"value\": \"The Account requested does not exist.\"}");
			ctx.status(404);
			return;
		}

		boolean isUser = !as.isActor();

		HashMap<String, Object> hm = new HashMap<>();
		hm.put("address", addr.longValue());
		hm.put("key", as.getAccountKey().toString());
		hm.put("allowance", as.getMemory());
		hm.put("balance", as.getBalance());
		hm.put("memorySize", as.getMemorySize());
		hm.put("sequence", as.getSequence());
		hm.put("type", isUser ? "user" : "actor");

		ctx.result(JSON.toPrettyString(hm));
	}

	public void queryPeer(Context ctx) {
		AccountKey addr = null;
		String addrParam = ctx.pathParam("addr");
		try {

			addr = AccountKey.parse(addrParam);
			if (addr == null)
				throw new BadRequestResponse(jsonError("Invalid peer key: " + addrParam));
		} catch (Exception e) {
			throw new BadRequestResponse(jsonError("Expected valid peer key in path but got [" + addrParam + "]"));
		}

		Result r = doQuery(Reader.read("(get-in *state* [:peers " + addr + "])"));

		if (r.isError()) {
			ctx.json(jsonResult(r));
			return;
		}

		PeerStatus as = r.getValue();
		if (as == null) {
			throw new NotFoundResponse("Peer does not exist: "+addrParam);
		}

		Object hm = JSON.from(as);

		ctx.result(JSON.toPrettyString(hm));
	}

	/**
	 * Runs a query, wrapping exceptions
	 * 
	 * @param form
	 * @return
	 */
	private Result doQuery(ACell form) {
		try {
			return convex.querySync(form);
		} catch (TimeoutException e) {
			throw new ServiceUnavailableResponse("Timeout in query request");
		} catch (IOException e) {
			throw new InternalServerErrorResponse("IOException in query request: " + e);
		} catch (Exception e) {
			throw new InternalServerErrorResponse("Failed to execute query: " + e);
		}
	}

	/**
	 * Runs a transaction, wrapping exceptions
	 * 
	 * @param form
	 * @return
	 */
	private Result doTransaction(SignedData<ATransaction> signedTransaction) {
		try {
			return convex.transactSync(signedTransaction);
		} catch (TimeoutException e) {
			throw new ServiceUnavailableResponse("Timeout executing transaction - unable to confirm result.");
		} catch (Exception e) {
			throw new InternalServerErrorResponse("Failed to execute transaction: " + e);
		}
	}

	private HashMap<String, Object> jsonResult(Result r) {
		HashMap<String, Object> hm=new HashMap<>();
		if (r.isError()) {
			hm.put("errorCode", RT.name(r.getErrorCode()).toString());
		} 
		hm.put("value", RT.json(r.getValue()));
		hm.put("info", RT.json(r.getInfo()));
		return hm;
	}

	public void faucetRequest(Context ctx) {
		Map<String, Object> req = getJSONBody(ctx);
		Address addr = Address.parse(req.get("address"));
		if (addr == null)
			throw new BadRequestResponse("Expected JSON body containing 'address' field");

		Object o = req.get("amount");
		CVMLong l = CVMLong.parse(o);
		if (l == null)
			throw new BadRequestResponse("faucet requires an 'amount' field containing a long value.");

		long amt = l.longValue();
		// Do any limits on faucet issue here
		if (amt > Coin.GOLD)
			amt = Coin.GOLD;

		try {
			// SECURITY: Make sure this is not subject to injection attack
			// Optional: pre-compile to Op
			Result r = convex.transactSync("(transfer " + addr + " " + amt + ")");
			if (r.isError()) {
				HashMap<String, Object> hm = jsonResult(r);
				ctx.json(hm);
			} else {
				req.put("amount", r.getValue());
				ctx.result(JSON.toPrettyString(req));
			}
		} catch (TimeoutException e) {
			throw new ServiceUnavailableResponse("Timeout in request");
		} catch (IOException e) {
			throw new InternalServerErrorResponse("Fauncet failure: "+e.getMessage());
		}

	}

	@OpenApi(path = ROUTE+"transaction/prepare",
			methods = HttpMethod.POST,
			operationId = "transactionPrepare",
			tags= {"Transactions"},
			summary="Prepare a Convex transaction. If sucessful, will return a hash to be signed.",
			requestBody = @OpenApiRequestBody(
					description = "Transaction preparation request",
					content= @OpenApiContent(
							from=TransactionPrepareRequest.class,
							type = "application/json", 
							exampleObjects = {
								@OpenApiExampleProperty(name = "address", value = "12"),
								@OpenApiExampleProperty(name = "source", value = "(* 2 3)")
							})),
			responses = {
					@OpenApiResponse(status = "200", 
							description = "Transaction prepared", 
							content = {
								@OpenApiContent(
										from=TransactionPrepareResponse.class,
										type = "application/json", 
										exampleObjects = {
											@OpenApiExampleProperty(name = "sequence", value = "14"),
											@OpenApiExampleProperty(name = "address", value = "12"),
											@OpenApiExampleProperty(name = "source", value = "(* 2 3)"),
											@OpenApiExampleProperty(name = "hash", value = "d00c0e81031103110232012a")
										}
										)}),
					@OpenApiResponse(status = "503", 
							description = "Transaction service unavailable" )
				}
			)
	public void runTransactionPrepare(Context ctx) {
		Map<String, Object> req = getJSONBody(ctx);
		Address addr = Address.parse(req.get("address"));
		if (addr == null)
			throw new BadRequestResponse("Transaction prepare requires a valid 'address' field.");
		
		Object srcValue = req.get("source");
		ACell code = readCode(srcValue);
		Object maybeSeq=req.get("sequence");

		try {
			long sequence;
			if (maybeSeq!=null) {
				CVMLong lv=CVMLong.parse(maybeSeq);
				if (lv==null) throw new BadRequestResponse("sequence (if provided) must be an integer");
				sequence=lv.longValue();
			} else {
				sequence = convex.getSequence(addr)+1;
			}
			ATransaction trans = Invoke.create(addr, sequence, code);
			Ref<ATransaction> ref = Cells.persist(trans).getRef();

			HashMap<String, Object> rmap = new HashMap<>();
			rmap.put("source", srcValue);
			rmap.put("address", RT.json(addr));
			rmap.put("hash", SignedData.getMessageForRef(ref).toHexString());
			rmap.put("sequence", sequence);

			ctx.result(JSON.toPrettyString(rmap));
		} catch (Exception e) {
			throw new InternalServerErrorResponse("Error preparing transaction: " + e.getMessage());
		}
	}

	@OpenApi(path = ROUTE+"transact",
			methods = HttpMethod.POST,
			operationId = "transact",
			tags= {"Transactions"},
			summary="Execute a Convex transaction. WARNING: sends Ed25519 seed over the network for peer to complete signature.",
			requestBody = @OpenApiRequestBody(
					description = "Transaction execution request",
					content= @OpenApiContent(
							from=TransactRequest.class,
							type = "application/json",
							exampleObjects = {
									@OpenApiExampleProperty(name = "address", value = "12"),
									@OpenApiExampleProperty(name = "source", value = "(* 2 3)"),
									@OpenApiExampleProperty(name = "seed", value = "0x690f51d2eb7163f820fdb861b33d5b077034f09923a2d31946ac199f28639506")
								}
							)),
			responses = {
					@OpenApiResponse(status = "200", 
							description = "Transaction executed", 
							content = {
								@OpenApiContent(
										from=CVMResult.class,
										type = "application/json", 
										exampleObjects = {
											@OpenApiExampleProperty(name = "value", value = "6")
										}
										)}),
					@OpenApiResponse(status = "503", 
							description = "Transaction service unavailable" )
				}
			)
	public void runTransact(Context ctx) {
		Map<String, Object> req = getJSONBody(ctx);
		if (!req.containsKey("seed") || req.containsKey("sig")) {
			runTransactionPrepare(ctx);
			return;
		}

		Address addr = Address.parse(req.get("address"));
		if (addr == null)
			throw new BadRequestResponse("Transact requires an valid address.");
		Object srcValue = req.get("source");
		ACell code = readCode(srcValue);

		// Get ED25519 seed
		ABlob seed = Blobs.parse(req.get("seed"));
		if (!(seed instanceof ABlob))
			throw new BadRequestResponse("Valid Ed25519 seed required for transact (hex string)");
		if (seed.count() != AKeyPair.SEED_LENGTH)
			throw new BadRequestResponse("Seed must be 32 bytes");

		try {
			long sequence = convex.getSequence(addr);
			long nextSeq = sequence + 1;
			ATransaction trans = Invoke.create(addr, nextSeq, code);

			AKeyPair kp = AKeyPair.create(seed.toFlatBlob());
			SignedData<ATransaction> sd = kp.signData(trans);

			Result r = doTransaction(sd);

			HashMap<String, Object> rm = jsonResult(r);
			ctx.result(JSON.toPrettyString(rm));
		} catch (NullPointerException e) {
			throw new BadRequestResponse("Account does not exist: " + addr);
		} catch (Exception e) {
			throw new InternalServerErrorResponse("Error executing transaction: " + e.getMessage());
		}
	}

	/**
	 * Read code on best efforts basis, expecting a String
	 * @param srcValue
	 * @return Object to interpret as code
	 */
	private static ACell readCode(Object srcValue) {
		try {
			return Reader.read((String) srcValue);
		} catch (Exception e) {
			throw new BadRequestResponse(jsonError("Source code could not be read: " + e.getMessage()));
		}
	}

	@OpenApi(path = ROUTE+"transaction/submit",
			methods = HttpMethod.POST,
			operationId = "transactionSubmit",
			tags= {"Transactions"},
			summary="Submit a pre-prepared Convex transaction. If sucessful, will return transaction result.",
			requestBody = @OpenApiRequestBody(
					description = "Transaction preparation request",
					content= @OpenApiContent(
							from=TransactionSubmitRequest.class,
							type = "application/json" 
							)),
			responses = {
					@OpenApiResponse(status = "200", 
							description = "Transaction executed", 
							content = {
								@OpenApiContent(
										from=CVMResult.class,
										type = "application/json", 
										exampleObjects = {
											@OpenApiExampleProperty(name = "value", value = "6")
										}
										)}),
					@OpenApiResponse(status = "503", 
							description = "Transaction service unavailable" )
				}
			)
	public void runTransactionSubmit(Context ctx) {
		Map<String, Object> req = getJSONBody(ctx);

		// Get the transaction hash
		Object hashValue = req.get("hash");
		if (!(hashValue instanceof String))
			throw new BadRequestResponse("Parameter 'hash' must be provided as a String");
		Blob h = Blob.parse((String) hashValue);
		if (h == null)
			throw new BadRequestResponse("Parameter 'hash' did not parse correctly, must be a hex string.");

		ATransaction trans = null;
		try {
			Ref<?> ref = Format.readRef(h, 0);
			ACell maybeTrans = ref.getValue();
			if (!(maybeTrans instanceof ATransaction))
				throw new BadRequestResponse("Value with hash " + h + " is not a transaction: can't submit it!");
			trans = (ATransaction) maybeTrans;
		} catch (MissingDataException e) {
			throw new BadRequestResponse("Could not load transaction with hash " + h + ": probably you need to call 'prepare' first?");
		} catch (Exception e) {
			throw new BadRequestResponse(
					jsonError("Failed to identify transaction with hash " + h + ": " + e.getMessage()));
		}

		// Get the account key
		Object keyValue = req.get("accountKey");
		if (!(keyValue instanceof String))
			throw new BadRequestResponse("Expected JSON body containing 'accountKey' field");
		AccountKey key = AccountKey.parse(keyValue);
		if (key == null)
			throw new BadRequestResponse(
					"Parameter 'accountKey' did not parse correctly, must be 64 hex characters (32 bytes).");

		// Get the signature
		Object sigValue = req.get("sig");
		if (!(sigValue instanceof String))
			throw new BadRequestResponse("Parameter 'sig' must be provided as a String");
		ABlob sigData = Blobs.parse(sigValue);
		if ((sigData == null) || (sigData.count() != Ed25519Signature.SIGNATURE_LENGTH)) {
			throw new BadRequestResponse("Parameter 'sig' must be a 64 byte hex String (128 hex chars)");
		}
		ASignature sig = Ed25519Signature.fromBlob(sigData);

		SignedData<ATransaction> sd = SignedData.create(key, sig, trans.getRef());
		Result r = doTransaction(sd);
		HashMap<String, Object> rm = jsonResult(r);
		ctx.result(JSON.toPrettyString(rm));
	}

	@OpenApi(path = ROUTE+"query",
		methods = HttpMethod.POST,
		operationId = "query",
		tags= {"Transactions"},
		summary="Query as Convex account",
		requestBody = @OpenApiRequestBody(
				description = "Query request",
				content= @OpenApiContent(
						from=QueryRequest.class,
						type = "application/json", 
						exampleObjects = {
							@OpenApiExampleProperty(name = "address", value = "12"),
							@OpenApiExampleProperty(name = "source", value = "(* 2 3)")
						})),
		responses = {
				@OpenApiResponse(status = "200", 
						description = "Query executed", 
						content = {
							@OpenApiContent(
									from=CVMResult.class,
									type = "application/json", 
									exampleObjects = {
										@OpenApiExampleProperty(name = "value", value = "6")
									}
									)}),
				@OpenApiResponse(status = "503", 
						description = "Query service unavailable" )
			}
		)
	public void runQuery(Context ctx) {
		Map<String, Object> req = getJSONBody(ctx);
		Address addr = Address.parse(req.get("address"));
		if (addr == null)
			throw new BadRequestResponse("query requires an 'address' field.");
		Object srcValue = req.get("source");
		ACell form = readCode(srcValue);

		try {
			Result r = convex.querySync(form, addr);
			HashMap<String, Object> rmap = jsonResult(r);

			ctx.result(JSON.toPrettyString(rmap));
		} catch (TimeoutException e) {
			throw new ServiceUnavailableResponse("Timeout in request");
		} catch (IOException e) {
			throw new InternalServerErrorResponse("IO Failure in query: "+e.getMessage());
		}
	}

}
