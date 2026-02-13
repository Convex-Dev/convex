package convex.restapi.api;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import convex.api.ContentTypes;
import convex.api.Convex;
import convex.core.Coin;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.cpos.Block;
import convex.core.cpos.Order;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.crypto.Ed25519Signature;
import convex.core.crypto.IdenticonBuilder;
import convex.core.cvm.AccountStatus;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.cvm.Peer;
import convex.core.cvm.PeerStatus;
import convex.core.cvm.Symbols;
import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.AArrayBlob;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Cells;
import convex.core.data.Format;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.Lists;
import convex.core.data.Maps;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.MissingDataException;
import convex.core.exceptions.ParseException;
import convex.core.exceptions.ResultException;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.util.JSON;
import convex.restapi.RESTServer;
import convex.restapi.handler.ConcurrentLimit;
import convex.restapi.model.CreateAccountRequest;
import convex.restapi.model.CreateAccountResponse;
import convex.restapi.model.FaucetRequest;
import convex.restapi.model.QueryAccountResponse;
import convex.restapi.model.QueryRequest;
import convex.restapi.model.ResultResponse;
import convex.restapi.model.TransactRequest;
import convex.restapi.model.TransactionPrepareRequest;
import convex.restapi.model.TransactionPrepareResponse;
import convex.restapi.model.TransactionSubmitRequest;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.InternalServerErrorResponse;
import io.javalin.http.NotFoundResponse;
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
		this.convex = restServer.getConvex();
	}

	private static final String ROUTE = "/api/v1/";

	private ConcurrentLimit faucetLimit=new ConcurrentLimit(10);
	private ConcurrentLimit identiconLimit=new ConcurrentLimit(10);
	private ConcurrentLimit transactLimit=new ConcurrentLimit(2);
	
	@Override
	public void addRoutes(Javalin app) {
		String prefix = ROUTE;

		app.post(prefix + "query", this::query);

		app.post(prefix + "transaction/prepare", this::transactionPrepare);
		app.post(prefix + "transaction/submit", this::transactionSubmit);
		app.post(prefix + "transact", transactLimit.handler(this::transact));

		app.post(prefix + "createAccount", faucetLimit.handler(this::createAccount));
		app.post(prefix + "faucet",  faucetLimit.handler(this::faucetRequest));


		app.get(prefix + "accounts/{addr}", this::queryAccount);
		app.get(prefix + "peers/{addr}", this::queryPeer);

	
		app.get(prefix + "data/{hash}", this::getData);
		app.post(prefix + "data/encode", this::encodeData);
		app.post(prefix + "data/decode", this::decodeData);
		
		
		app.get(prefix + "tx", this::getTransaction);
		
		app.get(prefix + "blocks", this::getBlocks);
		app.get(prefix + "blocks/{blockNum}", this::getBlock);
		
		app.get(prefix + "status", this::getStatus);
		
		app.get("/identicon/{hex}", identiconLimit.handler(this::getIdenticon));
	}

	@OpenApi(path = ROUTE + "data/{hash}", 
			versions="peer-v1",
			methods = HttpMethod.GET, 
			tags = { "Data Lattice"},
			summary = "Get data from the server with the specified hash", 
			operationId = "data", 
			pathParams = {
					@OpenApiParam(
							name = "hash", 
							description = "Data hash as a hex string. Leading '0x' is optional but discouraged.", 
							required = true, 
							type = String.class, 
							example = "0x1234567812345678123456781234567812345678123456781234567812345678") })
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
		setContent(ctx,d);
	}
	
	@OpenApi(path = ROUTE + "data/encode", 
			versions="peer-v1",
			methods = HttpMethod.POST, 
			tags = { "Data Lattice"},
			summary = "Encode data in CAD3 multi-cell format", 
			operationId = "encode",
			requestBody = @OpenApiRequestBody(
					description = "Encode request",
					content= {@OpenApiContent(
							from=QueryRequest.class,
							type = "application/json", 
							exampleObjects = {
								@OpenApiExampleProperty(name = "data", value = "12")
							}),
							@OpenApiContent(
								mimeType = "application/cvx",
								from=String.class,
								example="[1 2 3]"
							)
					}))
	public void encodeData(Context ctx) {
		String type=ctx.req().getContentType();
		ACell value;
		
		if (ContentTypes.JSON.equals(type)) {
			ACell body=this.readJSONBody(ctx);
			AString field=RT.ensureString(RT.getIn(body, Strings.DATA));
			if (field==null) throw new BadRequestResponse("Encode requires 'data' field");
			value=Reader.read(field.toString());
		} else if (ContentTypes.CVX.equals(type)||ContentTypes.TEXT.equals(type)) {
			try {
				value=Reader.read(ctx.bodyInputStream());
			} catch (Exception e) {
				throw new BadRequestResponse("Could not parse CVX content: "+e.getMessage());
			}
		} else {
			throw new BadRequestResponse("Expected JSON request or plain CVX data to encode");
		}
		
		Blob b=Format.encodeMultiCell(value, true);

		ctx.status(200);
		String responseType=this.calcResponseContentType(ctx);
		if (ContentTypes.CVX_RAW.equals(responseType)||ContentTypes.BYTES.equals(type)) {
			ctx.result(b.getInputStream());
		} else {
			AMap<AString, ACell> result = Maps.of(
				Strings.create("cad3"), Strings.create(b.toCVMHexString()),
				Strings.create("hash"), Strings.create(Ref.get(value).getEncoding().toCVMHexString())
			);
			this.setContent(ctx, result);
		}
		
	}
	
	@OpenApi(path = ROUTE + "data/decode", 
			versions="peer-v1",
			methods = HttpMethod.POST, 
			tags = { "Data Lattice"},
			summary = "Decode CAD3 data", 
			operationId = "decode",
			requestBody = @OpenApiRequestBody(
					description = "Decode request",
					content= {@OpenApiContent(
							from=QueryRequest.class,
							type = "application/json", 
							exampleObjects = {
								@OpenApiExampleProperty(name = "cad3", value = "0x110c")
							})
					}))
	public void decodeData(Context ctx) {
		String type=ctx.req().getContentType();
		ABlob value;
		
		if (ContentTypes.JSON.equals(type)) {
			ACell body=this.readJSONBody(ctx);
			AString field=RT.ensureString(RT.getIn(body, Strings.create("cad3")));
			if (field==null) throw new BadRequestResponse("Decode requires 'cad3' field");
			value=Blob.parse(field);
		} else if (ContentTypes.CVX.equals(type)||ContentTypes.BYTES.equals(type)) {
			try {
				value=Blobs.fromStream(ctx.bodyInputStream());
			} catch (Exception e) {
				throw new BadRequestResponse("Could not read CAD3 content: "+e.getMessage());
			}
		} else {
			throw new BadRequestResponse("Expected CAD3 data to decode");
		}
		
		ACell r;
		try {
			r = Format.decodeMultiCell(value.toFlatBlob());
		} catch (BadFormatException e) {
			this.failBadRequest("Error decoding CAD3 data - bad format");
			return;
		}

		ctx.status(200);
		String rtype=this.calcResponseContentType(ctx);
		if (ContentTypes.CVX_RAW.equals(rtype)) {
			ctx.result(RT.print(r).getInputStream());
		} else if (ContentTypes.JSON.equals(rtype)) {
			this.setContent(ctx, Maps.of("cvx",RT.print(r)));
		}
		
	}

	@OpenApi(path = ROUTE + "tx", 
			versions="peer-v1",
			methods = HttpMethod.GET, 
			tags = { "Transactions"},
			summary = "Get transaction by hash", 
			operationId = "getTransaction", 
			queryParams = {
					@OpenApiParam(
							name = "hash", 
							description = "Transaction hash as a hex string. Leading '0x' is optional.", 
							required = true, 
							type = String.class, 
							example = "0x1234567812345678123456781234567812345678123456781234567812345678") },
			responses = {
				@OpenApiResponse(
						status = "200", 
						description = "Transaction found", 
						content = {
							@OpenApiContent(
									type = "application/json") }),
				@OpenApiResponse(
						status = "400", 
						description = "Bad request, invalid hash format"),
				@OpenApiResponse(
						status = "404", 
						description = "Transaction not found")
			})
	public void getTransaction(Context ctx) {
		String hashParam = ctx.queryParam("hash");
		if (hashParam == null) {
			throw new BadRequestResponse("Missing required query parameter: hash");
		}
		
		Hash h = Hash.parse(hashParam);
		if (h == null) {
			throw new BadRequestResponse("Invalid hash: " + hashParam);
		}

		Peer peer=server.getPeer();
		
		SignedData<ATransaction> transaction = peer.getTransaction(h);
		if (transaction == null) {
			throw new NotFoundResponse("Transaction not found: " + hashParam);
		}
		
		AVector<CVMLong> pos=peer.getTransactionLocation(h);

		Result txResult=peer.getTransactionResult(pos);
		
		AMap<AString,ACell> result=Maps.of(
			Keywords.TX, transaction,
			Keywords.POSITION, pos,
			Keywords.RESULT, txResult
		);
		
		setContent(ctx,result);
	}

	@OpenApi(path = ROUTE + "blocks", 
			versions="peer-v1",
			methods = HttpMethod.GET, 
			tags = { "Blocks"},
			summary = "Get blocks with pagination", 
			operationId = "getBlocks", 
			queryParams = {
					@OpenApiParam(
							name = "offset", 
							description = "Starting index for blocks (0-based)", 
							required = false, 
							type = Long.class, 
							example = "0"),
					@OpenApiParam(
							name = "limit", 
							description = "Maximum number of blocks to return", 
							required = false, 
							type = Long.class, 
							example = "100") },
			responses = {
				@OpenApiResponse(
						status = "200", 
						description = "Blocks retrieved successfully", 
						content = {
							@OpenApiContent(
									type = "application/json") }),
				@OpenApiResponse(
						status = "400", 
						description = "Bad request, invalid offset or limit parameters")
			})
	public void getBlocks(Context ctx) {
		// Get pagination parameters
		String offsetParam = ctx.queryParam("offset");
		String limitParam = ctx.queryParam("limit");
		
		long offset = 0;
		long limit = 100; // Default limit
		
		try {
			if (offsetParam != null) {
				offset = Long.parseLong(offsetParam);
				if (offset < 0) {
					throw new BadRequestResponse("Offset must be non-negative");
				}
			}
			if (limitParam != null) {
				limit = Long.parseLong(limitParam);
				if (limit <= 0 || limit > 1000) {
					throw new BadRequestResponse("Limit must be between 1 and 1000");
				}
			}
		} catch (NumberFormatException e) {
			throw new BadRequestResponse("Invalid offset or limit parameter: must be a number");
		}
		
		// Get blocks from peer order
		Order peerOrder = server.getPeer().getPeerOrder();
		AVector<SignedData<Block>> blocks = peerOrder.getBlocks();
		long totalBlocks = blocks.count();
		
		// Get finality point for determining if blocks are finalised
		long finalityPoint = server.getPeer().getFinalityPoint();
		
		// Calculate actual range
		long start = Math.min(offset, totalBlocks);
		long end = Math.min(start + limit, totalBlocks);
		
		// Build response
		HashMap<String, Object> response = new HashMap<>();
		response.put("count", totalBlocks);
		response.put("offset", offset);
		
		// Extract block data for the requested range
		java.util.List<Map<String, Object>> items = new java.util.ArrayList<>();
		for (long i = start; i < end; i++) {
			SignedData<Block> signedBlock = blocks.get(i);
			HashMap<String, Object> blockData = getBlockData(signedBlock);
			blockData.put("index", i);
			blockData.put("finalised", i < finalityPoint);
			
			items.add(blockData);
		}
		
		response.put("items", items);
		
		ctx.result(JSON.toStringPretty(response));
	}

	/**
	 * Constructs a block data map from a SignedData<Block>
	 * @param signedBlock The signed block data
	 * @return HashMap containing block information
	 */
	private HashMap<String, Object> getBlockData(SignedData<Block> signedBlock) {
		Block block = signedBlock.getValue();
		
		HashMap<String, Object> blockData = new HashMap<>();
		blockData.put("timestamp", block.getTimeStamp());
		blockData.put("peer", signedBlock.getAccountKey().toString());
		blockData.put("hash", signedBlock.getHash().toString());
		blockData.put("transactionCount", block.getTransactions().count());
		
		return blockData;
	}

	@OpenApi(path = ROUTE + "blocks/{blockNum}", 
			versions="peer-v1",
			methods = HttpMethod.GET, 
			tags = { "Blocks"},
			summary = "Get a specific block by block number", 
			operationId = "getBlock", 
			pathParams = {
					@OpenApiParam(
							name = "blockNum", 
							description = "Block number (0-based index)", 
							required = true, 
							type = Long.class, 
							example = "0") },
			responses = {
				@OpenApiResponse(
						status = "200", 
						description = "Block found", 
						content = {
							@OpenApiContent(
									type = "application/json") }),
				@OpenApiResponse(
						status = "400", 
						description = "Bad request, invalid block number format"),
				@OpenApiResponse(
						status = "404", 
						description = "Block not found")
			})
	public void getBlock(Context ctx) {
		String blockNumParam = ctx.pathParam("blockNum");
		long blockNum;
		
		try {
			blockNum = Long.parseLong(blockNumParam);
			if (blockNum < 0) {
				throw new BadRequestResponse("Block number must be non-negative");
			}
		} catch (NumberFormatException e) {
			throw new BadRequestResponse("Invalid block number format: must be a number");
		}
		
		Peer peer=server.getPeer();
		
		// Get blocks from peer order
		Order peerOrder = peer.getPeerOrder();
		AVector<SignedData<Block>> blocks = peerOrder.getBlocks();
		long totalBlocks = blocks.count();
		
		// Check if block exists
		if (blockNum >= totalBlocks) {
			throw new NotFoundResponse("Block not found: " + blockNum);
		}
		
		// Get finality point for determining if block is finalised
		long finalityPoint = peer.getFinalityPoint();
		
		// Get the specific block
		SignedData<Block> signedBlock = blocks.get(blockNum);
		HashMap<String, Object> blockData = getBlockData(signedBlock);
		blockData.put("index", blockNum);
		blockData.put("finalised", blockNum < finalityPoint);
		
		ctx.result(JSON.toStringPretty(blockData));
	}

	@OpenApi(path = ROUTE + "status", 
			versions="peer-v1",
			methods = HttpMethod.GET, 
			tags = { "Peer"},
			summary = "Get the status map from the peer server. Can be used as a heartbeat check to ensure the peer is still running.", 
			operationId = "getStatus", 
			responses = {
				@OpenApiResponse(
						status = "200", 
						description = "Status retrieved successfully", 
						content = {
							@OpenApiContent(
									type = "application/json") }),
			})
	public void getStatus(Context ctx) {
		AMap<Keyword,ACell> statusMap = server.getStatusMap();
		setContent(ctx, statusMap);
	}

	@OpenApi(path = ROUTE + "createAccount", 
			versions="peer-v1",
			methods = HttpMethod.POST, 
			operationId = "createAccount", 
			tags = { "Account"},
			summary = "Create a new Convex account. Requires a peer willing to accept faucet requests.", 
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
						description = "Account creation executed", 
						content = {
							@OpenApiContent(
									type = "application/json", 
									from = CreateAccountResponse.class) }),
				@OpenApiResponse(
						status = "400", 
						description = "Bad request, probably a missing or invalid accountKey")
				})
	public void createAccount(Context ctx) throws InterruptedException {
		Convex faucetClient=restServer.getFaucet();
		if (faucetClient==null) throw new ForbiddenResponse("Faucet use not authorised on this server");

		AMap<AString, ACell> req = readJSONBody(ctx);
		AString key = req.getIn("accountKey");
		if (key == null)
			throw new BadRequestResponse(jsonError("Expected JSON body containing 'accountKey' field"));

		AccountKey pk = AccountKey.parse(key);
		if (pk == null)
			throw new BadRequestResponse(jsonError("Unable to parse accountKey: " + key));

		ACell faucet = req.getIn("faucet");
		AInteger amt = AInteger.parse(faucet);
		
		Address a;
		try {
			a = faucetClient.createAccountSync(pk);
			if (amt != null) {
				faucetClient.transferSync(a, amt.longValue());
			}
		} catch (ResultException e) {
			setContent(ctx,e.getResult());
			return;
		}
		ctx.result("{\"address\": " + a.longValue() + "}");
	}

	@OpenApi(path = ROUTE + "accounts/{address}", 
			versions="peer-v1",
			methods = HttpMethod.GET, 
			operationId = "queryAccount", 
			tags = { "Account"},
			summary = "Get Convex account information", 
			pathParams = {
				 @OpenApiParam(name = "address", description = "Address of Account", required = true, type = String.class, example="14")
			},
			responses = {
				@OpenApiResponse(status = "200", 
						description = "Account queried successfully", 
						content = {
							@OpenApiContent(
									from=QueryAccountResponse.class,
									type = "application/json") }),
				@OpenApiResponse(
						status = "400", 
						description = "Bad request, probably an invalid address parameter"),
				@OpenApiResponse(status = "404", 
						description = "Account does not exist" )
			}
		)
	public void queryAccount(Context ctx) throws InterruptedException {
		Address addr = null;
		String addrParam = ctx.pathParam("addr");

		addr = Address.parse(addrParam);
		if (addr == null) {
			throw new BadRequestResponse(jsonError("Invalid address: " + addrParam));
		}

		Result r = convex.querySync(Lists.of(Symbols.ACCOUNT, addr));

		if (r.isError()) {
			setContent(ctx,r);
			return;
		}

		AccountStatus as = r.getValue();
		if (as == null) {
			ctx.result("{\"errorCode\": \"NOBODY\",\"value\": \"The Account requested does not exist.\"}");
			ctx.contentType("application/json");
			ctx.status(404);
			return;
		}

		String jsonAccountInfo = JSON.toString(getAccountInfo(addr, as));
		
		ctx.result(jsonAccountInfo);
	}

	public static HashMap<String, Object> getAccountInfo(Address addr, AccountStatus as) {
		boolean isUser = !as.isActor();
		AccountKey publicKey=as.getAccountKey();

		HashMap<String, Object> hm = new HashMap<>();
		hm.put("address", addr.longValue());
		hm.put("key", publicKey==null?null:publicKey.toString());
		hm.put("allowance", as.getMemory());
		hm.put("balance", as.getBalance());
		hm.put("memorySize", as.getMemorySize());
		hm.put("sequence", as.getSequence());
		hm.put("type", isUser ? "user" : "actor");
		return hm;
	}

	
	public void queryPeer(Context ctx) throws InterruptedException {
		AccountKey addr = null;
		String addrParam = ctx.pathParam("addr");

		addr = AccountKey.parse(addrParam);
		if (addr == null) {
			throw new BadRequestResponse(jsonError("Invalid peer key: " + addrParam));
		}
 
		Result r = convex.querySync(Reader.read("(get-in *state* [:peers " + addr + "])"));

		if (r.isError()) {
			setContent(ctx,r);
			return;
		}

		PeerStatus as = r.getValue();
		if (as == null) {
			throw new NotFoundResponse("Peer does not exist: "+addrParam);
		}

		ctx.result(JSON.toString(as));
	}

	public static final Keyword K_FAUCET=Keyword.intern("faucet");
	
	@OpenApi(path = ROUTE + "faucet", 
			versions="peer-v1",
			methods = HttpMethod.POST, 
			operationId = "faucetRequest", 
			tags = { "Account"},
			summary = "Request coins from a Faucet provider. Requires a peer willing to accept faucet requests.", 
			requestBody = @OpenApiRequestBody(
				description = "Faucet request, must provide an address for coins to be deposited in", 
				content = {@OpenApiContent(
								from = FaucetRequest.class, 
								type = "application/json", 
								exampleObjects = {
										@OpenApiExampleProperty(name = "address", value = "11"),
										@OpenApiExampleProperty(name = "amount", value = "10000000")})}
			), 
			responses = {
				@OpenApiResponse(
						status = "200", 
						description = "Faucet request executed", 
						content = {
							@OpenApiContent(
									type = "application/json", 
									from = CreateAccountResponse.class)}),
				@OpenApiResponse(
						status = "400", 
						description = "Bad request, probably e.g.  missing or invalid recipient address"),
				@OpenApiResponse(
						status = "422", 
						description = "Faucet request failed", 
						content = {
							@OpenApiContent(
									type = "application/json", 
									from = ResultResponse.class)}),
				@OpenApiResponse(
						status = "403", 
						description = "Faucet request forbidden, probably Server is not accepting faucet requests")
				})
	public void faucetRequest(Context ctx) throws InterruptedException {
		Convex faucetClient=restServer.getFaucet();
		if (faucetClient==null) throw new ForbiddenResponse("Faucet use not authorised on this server");

		AMap<AString, ACell> req = readJSONBody(ctx);
		Address addr = Address.parse(req.getIn("address"));
		if (addr == null) failBadRequest("Expected JSON body containing valid 'address' field");

		ACell o = req.getIn("amount");
		CVMLong l = CVMLong.parse(o);
		if (l == null) {failBadRequest("Faucet requires an 'amount' field containing a long value."); return;}

		long amt = l.longValue();
		// Do any limits on faucet issue here
		if (amt > Coin.GOLD)
			amt = Coin.GOLD;

		// SECURITY: Make sure this is not subject to injection attack
		// Optional: pre-compile to Op
		Result r = faucetClient.transactSync("(transfer " + addr + " " + amt + ")");
		if (r.isError()) {
			setContent(ctx,r);
			ctx.status(422);
		} else {
			req=req.assoc(Strings.ADDRESS, RT.castLong(addr));
			req=req.assoc(Strings.AMOUNT, r.getValue());
			setContent(ctx,req);
		}
	}

	/**
	 *  Throws a bad request exception, with the given message, formatted as a result
	 * @param message Message to include as error value
	 */
	protected void failBadRequest(String message) {
		HashMap<String, Object> hm = new HashMap<>();
		hm.put("errorCode","FAILED");
		hm.put("value", message);
		failBadRequest(hm);
	}
	
	protected void failBadRequest(HashMap<String, Object> result) {
		throw new BadRequestResponse(JSON.toString(result));
	}





	@OpenApi(path = ROUTE+"transaction/prepare",
			versions="peer-v1",
			methods = HttpMethod.POST,
			operationId = "transactionPrepare",
			tags= {"Transactions"},
			summary="Prepare a Convex transaction. If sucessful, will return an encoding to be signed.",
			requestBody = @OpenApiRequestBody(
					description = "Transaction preparation request",
					content= {
						@OpenApiContent(
							from=TransactionPrepareRequest.class,
							type = "application/json", 
							exampleObjects = {
								@OpenApiExampleProperty(name = "address", value = "12"),
								@OpenApiExampleProperty(name = "source", value = "(* 2 3)")
							})
						}),
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
											@OpenApiExampleProperty(name = "hash", value = "d00c0e81031103110232012a"),
											@OpenApiExampleProperty(name = "data", value = "d00c0e81031103110232012a")
										}
										)}),
					@OpenApiResponse(status = "503", 
							description = "Transaction service unavailable" )
				}
			)
	public void transactionPrepare(Context ctx) throws InterruptedException, IOException {
		Map<String, Object> req = getJSONBody(ctx);
		Address addr = Address.parse(req.get("address"));
		if (addr == null)
			throw new BadRequestResponse("Transaction prepare requires a valid 'address' field.");
		
		Object srcValue = req.get("source");
		ACell code = readCode(srcValue);
		Object maybeSeq=req.get("sequence");

		long sequence;
		try {
			if (maybeSeq!=null) {
				CVMLong lv=CVMLong.parse(maybeSeq);
				if (lv==null) throw new BadRequestResponse("sequence (if provided) must be an integer");
				sequence=lv.longValue();
			} else {
				sequence = convex.getSequence(addr)+1;
			}
		} catch (ResultException e) {
			setContent(ctx,e.getResult());
			return;
		}

		ATransaction trans = Invoke.create(addr, sequence, code);
		trans=Cells.persist(trans, server.getStore()); // persist data so we have a full copy if needed
		Ref<ATransaction> ref = trans.getRef();
		HashMap<String, Object> result = new HashMap<>();
		result.put("source", srcValue);
		result.put("address", JSON.json(addr));
		result.put("hash", SignedData.getMessageForRef(ref).toHexString());
		result.put("data", Format.encodeMultiCell(trans, true).toHexString());
		result.put("sequence", sequence);
		ctx.status(200);
		ctx.result(JSON.toString(result));
	}


	@SuppressWarnings("unchecked")
	@OpenApi(path = ROUTE+"transact",
			versions="peer-v1",
			methods = HttpMethod.POST,
			operationId = "transact",
			tags= {"Transactions"},
			summary="Execute a Convex transaction. WARNING: sends Ed25519 seed over the network for peer to complete signature. Only do this with a secure HTTPS connection to a peer that you trust.",
			requestBody = @OpenApiRequestBody(
					description = "Transaction execution request",
					content= {@OpenApiContent(
							from=TransactRequest.class,
							type = "application/json",
							exampleObjects = {
									@OpenApiExampleProperty(name = "address", value = "12"),
									@OpenApiExampleProperty(name = "source", value = "(* 2 3)"),
									@OpenApiExampleProperty(name = "seed", value = "0x0026a11f81cd2a7df7e00e3a55c4e9817b3bb4d3ed6252117d7d22923d4be24d")
								}
							),
							@OpenApiContent(
								type = "application/cvx-raw"
							)}),
			responses = {
					@OpenApiResponse(status = "200", 
							description = "Transaction executed successfully", 
							
							content = {
								@OpenApiContent(
									
										from=ResultResponse.class,
										type = "application/json", 
										exampleObjects = {
											@OpenApiExampleProperty(name = "value", value = "6"),
											@OpenApiExampleProperty(name = "info", objects={ 
												@OpenApiExampleProperty(name = "juice", value = "581"),
												@OpenApiExampleProperty(name = "tx", value = "0x9e328480aef5490ca864c1c1d8881c34b51e8499b59145d3bd6e06bcc6f1ddaf"),
												@OpenApiExampleProperty(name = "source", value = "SERVER"),
												@OpenApiExampleProperty(name = "fees", value = "13810"),
												@OpenApiExampleProperty(name = "loc", value = "[0, 0]")
											})										
										}
								)}),
					@OpenApiResponse(status = "422", 
					description = "Transaction failed", 
					content = {
						@OpenApiContent(
								from=ResultResponse.class,
								type = "application/json", 
								exampleObjects = {
									@OpenApiExampleProperty(name = "errorCode", value = ":NOBODY"),
									@OpenApiExampleProperty(name = "value", value = "Account does not exist")
								}
								)}),

					@OpenApiResponse(status = "503", 
							description = "Transaction service unavailable" )
				}
			)
	public void transact(Context ctx) throws InterruptedException, IOException {
		String type=ctx.req().getContentType();
		SignedData<ATransaction> sd;
		
		if (ContentTypes.CVX_RAW.equals(type)) {
			// Can accept a raw convex signed transaction
			ACell c=getRawBody(ctx);
			if ((c instanceof SignedData)&&(((SignedData<?>) c).getValue() instanceof ATransaction)) {
				sd=(SignedData<ATransaction>) c;
				// System.out.println("tx enc: "+sd.getEncoding());
			} else {
				throw new BadRequestResponse("Expected signed transaction but got: "+c);
			}
		} else {
			// Assume JSON type using simple form including seed
			Map<String, Object> req = getJSONBody(ctx);
	
			Address addr = Address.parse(req.get("address"));
			if (addr == null)
				throw new BadRequestResponse("Transact requires a valid address.");
			Object srcValue = req.get("source");
			ACell code = readCode(srcValue);
	
			// Get ED25519 seed
			ABlob seed = Blobs.parse(req.get("seed"));
			if (!(seed instanceof ABlob))
				throw new BadRequestResponse("Valid Ed25519 seed required for transact (hex string)");
			if (seed.count() != AKeyPair.SEED_LENGTH)
				throw new BadRequestResponse("Seed must be 32 bytes");
	
			long nextSeq;
			try {
				long sequence = convex.getSequence(addr);
				nextSeq = sequence + 1;
			} catch (ResultException e) {
				setContent(ctx,e.getResult());
				return;
			} 
		
			ATransaction trans = Invoke.create(addr, nextSeq, code);
			AKeyPair kp = AKeyPair.create(seed.toFlatBlob());
			sd = kp.signData(trans);
		} 

		Result r = convex.transactSync(sd);
		setContent(ctx,r);
	}

	/**
	 * Read code on best efforts basis, expecting a String
	 * @param srcValue Source value to read
	 * @return Object to interpret as code
	 * @throws BadRequestResponse if srcValue is not a valid String
	 */
	private static ACell readCode(Object srcValue) {
		if (!(srcValue instanceof String)) {
			throw new BadRequestResponse("Source code must be a string");
		}
		return Reader.read((String) srcValue);
	}

	@OpenApi(path = ROUTE+"transaction/submit",
			versions="peer-v1",
			methods = HttpMethod.POST,
			operationId = "transactionSubmit",
			tags= {"Transactions"},
			summary="Submit a pre-prepared Convex transaction. If successful, will return transaction result.",
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
										from=ResultResponse.class,
										type = "application/json", 
										exampleObjects = {
											@OpenApiExampleProperty(name = "value", value = "6"),
											@OpenApiExampleProperty(name = "info", 
											objects={ 
												@OpenApiExampleProperty(name = "juice", value = "581"),
												@OpenApiExampleProperty(name = "tx", value = "0x9e328480aef5490ca864c1c1d8881c34b51e8499b59145d3bd6e06bcc6f1ddaf"),
												@OpenApiExampleProperty(name = "source", value = "SERVER"),
												@OpenApiExampleProperty(name = "fees", value = "13810"),
												@OpenApiExampleProperty(name = "loc", value = "[0, 0]")
											})
										})}),
					@OpenApiResponse(status = "503", 
							description = "Transaction service unavailable" )
				}
			)
	public void transactionSubmit(Context ctx) throws InterruptedException {
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
				throw new BadFormatException("Value with hash " + h + " is not a transaction: can't submit it!");
			trans = (ATransaction) maybeTrans;
		} catch (MissingDataException e) {
			setContent(ctx,Result.error(ErrorCodes.MISSING, "Missing data for transaction. Possible need to prepare first?"));
			ctx.status(404);
			return;
		} catch (BadFormatException e) {
			setContent(ctx,Result.error(ErrorCodes.FORMAT, "Bad format: "+e));
			ctx.status(400);
			return;
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
		Result r = convex.transactSync(sd);
		setContent(ctx,r);
	}

	@OpenApi(path = ROUTE+"query",
		versions="peer-v1",
		methods = HttpMethod.POST,
		operationId = "query",
		tags= {"Transactions"},
		summary="Query as Convex account",
		requestBody = @OpenApiRequestBody(
				description = "Query request",
				content= {@OpenApiContent(
						from=QueryRequest.class,
						type = "application/json", 
						exampleObjects = {
							@OpenApiExampleProperty(name = "address", value = "12"),
							@OpenApiExampleProperty(name = "source", value = "(* 2 3)")
						}),
						@OpenApiContent(
							mimeType = "application/cvx",
							from=String.class,
							example="{\n  :address #12 \n  :source (* 2 3)\n}"
						)
				}),
		responses = {
				@OpenApiResponse(status = "200", 
						description = "Query executed. Result could be a CVM error, but query itself was valid", 
						content = {
							@OpenApiContent(
									from=ResultResponse.class,
									type = "application/json", 
									exampleObjects = {
										@OpenApiExampleProperty(name = "result", value = "6")
									}
									)}),
				@OpenApiResponse(status = "422", 
				description = "Query failed due to bad input", 
				content = {
					@OpenApiContent(
							from=ResultResponse.class,
							type = "application/json", 
							exampleObjects = {
								@OpenApiExampleProperty(name = "error", value = "SYNTAX"),
								@OpenApiExampleProperty(name = "result", value = "Bad syntax")
							}
							)}),
				@OpenApiResponse(status = "503", 
						description = "Query service unavailable" )
			}
		)
	public void query(Context ctx) throws InterruptedException {
		try {
			Address addr;
			ACell form;
			String type=ctx.req().getContentType();
			
			if (ContentTypes.CVX.equals(type)) {
				ACell body=getCVXBody(ctx);
				if (!(body instanceof AMap)) {
					throw new BadRequestResponse("query body is not a map.");
				}
				@SuppressWarnings("unchecked")
				AMap<Keyword,ACell> req=(AMap<Keyword, ACell>) body;
				addr=Address.parse(RT.get(req, Keywords.ADDRESS));
				form=RT.get(req, Keywords.SOURCE);
			} else {
				AMap<AString, ACell> req = readJSONBody(ctx);
				// System.out.println("query data: "+req+ " of type "+Utils.getClassName(req));
				addr = Address.parse(req.get(Strings.ADDRESS));
				AString srcValue = RT.ensureString(req.get(Strings.SOURCE));
				// System.out.println("query source: "+srcValue);
				form = Reader.read(srcValue);
			}
	
			Result r = convex.querySync(form, addr);
			setContent(ctx,r);
		} catch (ParseException e) {
			throw new BadRequestResponse(e.getMessage());
		}
	}
	
	@OpenApi(path = "/identicon/{hex}", 
			versions="peer-v1",
			methods = HttpMethod.GET, 
			tags = { "Utility"},
			summary = "Get the identicon for a hash / public key", 
			operationId = "getIdenticon", 
			pathParams = {
					@OpenApiParam(
							name = "hex", 
							description = "Hex string. Leading '0x' is optional but discouraged.", 
							required = true, 
							type = String.class, 
							example = "0x1234567812345678123456781234567812345678123456781234567812345678") },
			responses = {
				@OpenApiResponse(
						status = "200", 
						description = "Transaction found", 
						content = {
							@OpenApiContent(
									type = "image/png") }),
				@OpenApiResponse(
						status = "400", 
						description = "Bad request, invalid hash format")
			})
	public void getIdenticon(Context ctx) {
		String hexParam = ctx.pathParam("hex");
		
		// Parse hex string to blob
		AArrayBlob data = Blob.parse(hexParam);
		if (data == null) {
			throw new BadRequestResponse("Invalid hex string for identicon: " + hexParam);
		}
		
		try {
			// Generate identicon data
			int[] identiconData = IdenticonBuilder.build(data);
			
			// Create BufferedImage from identicon data
			BufferedImage image = new BufferedImage(IdenticonBuilder.SIZE, IdenticonBuilder.SIZE, BufferedImage.TYPE_INT_RGB);
			image.setRGB(0, 0, IdenticonBuilder.SIZE, IdenticonBuilder.SIZE, identiconData, 0, IdenticonBuilder.SIZE);
			
			// Convert to PNG bytes
			ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
			ImageIO.write(image, "PNG", byteStream);
			byte[] pngBytes = byteStream.toByteArray();
			
			// Set response headers for caching and content type
			ctx.header("Content-Type", "image/png");
			ctx.header("Cache-Control", "public, max-age=31536000"); // 1 year cache
			ctx.header("ETag", "\"" + data.toHexString() + "\""); // Use data as ETag
			ctx.result(pngBytes);
			
		} catch (IOException e) {
			throw new InternalServerErrorResponse("Failed to generate identicon: " + e.getMessage());
		}
	}
}
