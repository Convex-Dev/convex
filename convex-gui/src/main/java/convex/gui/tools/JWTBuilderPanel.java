package convex.gui.tools;

import java.awt.BorderLayout;
import java.awt.Color;
import java.security.SecureRandom;
import java.util.UUID;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;

import convex.auth.did.DID;
import convex.auth.jwt.JWT;
import convex.auth.ucan.UCAN;
import convex.auth.ucan.UCANValidator;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.wallet.AWalletEntry;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Ref;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.gui.components.ActionButton;
import convex.gui.components.ActionPanel;
import convex.gui.components.CodeLabel;
import convex.gui.components.account.KeyPairCombo;
import convex.gui.keys.UnlockWalletDialog;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

/**
 * Builder for EdDSA-signed JWT access tokens and JWT-encoded UCANs.
 */
@SuppressWarnings("serial")
public class JWTBuilderPanel extends JPanel {

	static final AString CLIENT_ID=Strings.intern("client_id");
	static final AString SCOPE=Strings.intern("scope");
	static final AString NOT_BEFORE=Strings.intern("nbf");
	static final AString TOKEN_ID=Strings.intern("jti");

	private static final AString[] ACCESS_RESERVED={
		JWT.ISS,JWT.SUB,JWT.AUD,JWT.EXP,NOT_BEFORE,JWT.IAT,TOKEN_ID,CLIENT_ID,SCOPE
	};

	private static final SecureRandom RANDOM=new SecureRandom();

	private final KeyPairCombo keyCombo;
	private final JTabbedPane modeTabs=new JTabbedPane();

	private final JTextField accessIssuer=new JTextField();
	private final JTextField accessSubject=new JTextField();
	private final JTextField accessAudience=new JTextField();
	private final JSpinner accessIssuedAt=timestampSpinner(0);
	private final JCheckBox accessUseNotBefore=new JCheckBox("Include");
	private final JSpinner accessNotBefore=timestampSpinner(0);
	private final JSpinner accessExpiry=timestampSpinner(0);
	private final JTextField accessTokenID=new JTextField();
	private final JTextField accessClientID=new JTextField();
	private final JTextField accessScope=new JTextField();
	private final JTextArea accessExtraClaims=jsonArea("{}");

	private final JTextField ucanIssuer=new JTextField();
	private final JTextField ucanAudience=new JTextField();
	private final JCheckBox ucanUseNotBefore=new JCheckBox("Include");
	private final JSpinner ucanNotBefore=timestampSpinner(0);
	private final JSpinner ucanExpiry=timestampSpinner(0);
	private final JTextField ucanNonce=new JTextField();
	private final JTextArea ucanCapabilities=jsonArea("[]");
	private final JTextArea ucanProofs=jsonArea("[]");
	private final JTextArea ucanFacts=jsonArea("");

	private final CodeLabel tokenArea=outputArea(true);
	private final CodeLabel headerArea=outputArea(false);
	private final CodeLabel payloadArea=outputArea(false);
	private final JTextArea infoArea=new JTextArea();

	private String previousSignerDID;

	public JWTBuilderPanel() {
		setLayout(new BorderLayout());

		JPanel instructions=new JPanel();
		instructions.add(new JLabel(
			"Build EdDSA JWT access tokens or UCAN delegations with a keyring signer"));
		add(instructions,BorderLayout.NORTH);

		JPanel inputPanel=new JPanel(new BorderLayout());
		JPanel signerPanel=new JPanel(new MigLayout("fillx,insets 10","[][grow]","[]"));
		signerPanel.add(new JLabel("Signing key:"));
		keyCombo=KeyPairCombo.create();
		signerPanel.add(keyCombo,"growx");
		inputPanel.add(signerPanel,BorderLayout.NORTH);

		modeTabs.addTab("Access Token",new JScrollPane(createAccessPanel()));
		modeTabs.addTab("UCAN",new JScrollPane(createUCANPanel()));
		inputPanel.add(modeTabs,BorderLayout.CENTER);

		ActionButton signButton=new ActionButton("Build and Sign",0xe5ca,e -> buildAndSign());
		signButton.setToolTipText("Build the selected token and sign it with the selected key");
		JPanel signRow=new JPanel();
		signRow.add(signButton);
		inputPanel.add(signRow,BorderLayout.SOUTH);

		JPanel outputPanel=createOutputPanel();

		JSplitPane splitPane=new JSplitPane(JSplitPane.VERTICAL_SPLIT,inputPanel,outputPanel);
		splitPane.setOneTouchExpandable(true);
		splitPane.setResizeWeight(0.56);
		add(splitPane,BorderLayout.CENTER);

		JPanel actions=new ActionPanel();
		JButton resetButton=new JButton("New Token",Toolkit.menuIcon(0xe5d5));
		resetButton.setToolTipText("Reset claims and generate fresh timestamps and identifiers");
		resetButton.addActionListener(e -> reset());
		actions.add(resetButton);
		add(actions,BorderLayout.SOUTH);

		accessUseNotBefore.addActionListener(e ->
			accessNotBefore.setEnabled(accessUseNotBefore.isSelected()));
		ucanUseNotBefore.addActionListener(e ->
			ucanNotBefore.setEnabled(ucanUseNotBefore.isSelected()));
		keyCombo.addItemListener(e -> updateSignerDefaults());

		infoArea.setRows(4);
		infoArea.setEditable(false);
		infoArea.setBackground(null);
		infoArea.setFont(Toolkit.MONO_FONT);
		infoArea.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createEmptyBorder(8,8,8,8),
			BorderFactory.createRaisedBevelBorder()));

		reset();
	}

	private JPanel createAccessPanel() {
		JPanel panel=formPanel();
		addField(panel,"Issuer (iss):",accessIssuer,
			"Token issuer. Defaults to the selected signing key's did:key.");
		addField(panel,"Subject (sub):",accessSubject,
			"Principal represented by the access token.");
		addField(panel,"Audience (aud):",accessAudience,
			"Resource server or API expected to accept the token.");
		addField(panel,"Issued at (iat):",accessIssuedAt,
			"Unix timestamp in seconds when the token was issued.");
		addOptionalTime(panel,"Not before (nbf):",accessUseNotBefore,accessNotBefore,
			"Optional Unix timestamp before which the token must not be accepted.");
		addField(panel,"Expiry (exp):",accessExpiry,
			"Unix timestamp in seconds when the token expires.");
		addField(panel,"JWT ID (jti):",accessTokenID,
			"Unique token identifier.");
		addField(panel,"Client ID:",accessClientID,
			"OAuth client_id claim required by the JWT access-token profile.");
		addField(panel,"Scope:",accessScope,
			"Optional space-separated OAuth scopes.");
		addArea(panel,"Additional claims:",accessExtraClaims,
			"JSON object containing additional non-standard claims.");
		return panel;
	}

	private JPanel createUCANPanel() {
		JPanel panel=formPanel();
		ucanIssuer.setEditable(false);
		addField(panel,"Issuer (iss):",ucanIssuer,
			"Issuer DID. Defaults to the selected signing key's did:key.");
		addField(panel,"Audience (aud):",ucanAudience,
			"did:key of the principal receiving the delegated capabilities.");
		addOptionalTime(panel,"Not before (nbf):",ucanUseNotBefore,ucanNotBefore,
			"Optional Unix timestamp before which the UCAN must not be accepted.");
		addField(panel,"Expiry (exp):",ucanExpiry,
			"Unix timestamp in seconds when the UCAN expires.");
		addField(panel,"Nonce (nnc):",ucanNonce,
			"Unique nonce used by applications for replay protection.");
		addArea(panel,"Capabilities (att):",ucanCapabilities,
			"JSON array of capability objects, e.g. [{\"with\":\"did:key:.../w/\",\"can\":\"crud/read\"}].");
		addArea(panel,"Proofs (prf):",ucanProofs,
			"JSON array of parent UCAN JWT strings.");
		addArea(panel,"Facts (fct):",ucanFacts,
			"Optional JSON value carrying signed facts; leave blank to omit.");
		return panel;
	}

	private JPanel createOutputPanel() {
		JPanel panel=new JPanel(new BorderLayout());
		JTabbedPane outputTabs=new JTabbedPane();
		outputTabs.addTab("Compact JWT",new JScrollPane(tokenArea));
		outputTabs.addTab("Protected Header",new JScrollPane(headerArea));
		outputTabs.addTab("Claims",new JScrollPane(payloadArea));
		panel.add(outputTabs,BorderLayout.CENTER);
		panel.add(infoArea,BorderLayout.SOUTH);
		return panel;
	}

	private void buildAndSign() {
		try {
			AKeyPair keyPair=getKeyPair();
			if (keyPair==null) return;

			boolean ucanMode=(modeTabs.getSelectedIndex()==1);
			AMap<AString,ACell> claims=ucanMode ? buildUCANFromFields() : buildAccessFromFields();
			AString token=signToken(claims,keyPair,ucanMode);
			showResult(token,keyPair,ucanMode);
		} catch (Exception ex) {
			clearOutput();
			infoArea.setText("Cannot build token: "+message(ex));
		}
	}

	private AMap<AString,ACell> buildAccessFromFields() {
		AMap<AString,ACell> extra=parseMap(accessExtraClaims.getText(),"Additional claims");
		Long notBefore=accessUseNotBefore.isSelected() ? spinnerLong(accessNotBefore) : null;
		return buildAccessClaims(
			accessIssuer.getText(),accessSubject.getText(),accessAudience.getText(),
			spinnerLong(accessIssuedAt),notBefore,spinnerLong(accessExpiry),
			accessTokenID.getText(),accessClientID.getText(),accessScope.getText(),extra);
	}

	private AMap<AString,ACell> buildUCANFromFields() {
		AVector<ACell> capabilities=parseVector(ucanCapabilities.getText(),"Capabilities");
		AVector<ACell> proofs=parseVector(ucanProofs.getText(),"Proofs");
		ACell facts=parseOptionalJSON(ucanFacts.getText(),"Facts");
		Long notBefore=ucanUseNotBefore.isSelected() ? spinnerLong(ucanNotBefore) : null;
		return buildUCANClaims(
			requiredDIDKey(ucanIssuer.getText(),"Issuer"),
			requiredDIDKey(ucanAudience.getText(),"Audience"),
			notBefore,spinnerLong(ucanExpiry),
			ucanNonce.getText(),capabilities,proofs,facts);
	}

	private AKeyPair getKeyPair() {
		AWalletEntry walletEntry=keyCombo.getWalletEntry();
		if (walletEntry==null) {
			infoArea.setText("No signing key selected");
			return null;
		}
		if (walletEntry.isLocked()&&!UnlockWalletDialog.offerUnlock(this,walletEntry)) {
			infoArea.setText("Signing key remains locked");
			return null;
		}
		AKeyPair keyPair=walletEntry.getKeyPair();
		if (keyPair==null) infoArea.setText("Selected keyring entry has no key pair");
		return keyPair;
	}

	private void showResult(AString token,AKeyPair keyPair,boolean ucanMode) {
		JWT parsed=JWT.parse(token);
		if (parsed==null) throw new IllegalStateException("Signed token could not be parsed");

		tokenArea.setText(token.toString());
		headerArea.setText(JSON.printPretty(parsed.getHeader()).toString());
		payloadArea.setText(JSON.printPretty(parsed.getClaims()).toString());

		boolean signatureValid=parsed.verifyEdDSA(keyPair.getAccountKey());
		long now=System.currentTimeMillis()/1000;
		StringBuilder info=new StringBuilder();
		info.append("Type:       ").append(ucanMode?"Convex UCAN":"JWT access token").append('\n');
		info.append("Signed By:  ").append(DID.forKey(keyPair.getAccountKey())).append('\n');
		info.append("Signature:  ").append(signatureValid?"VALID":"INVALID").append('\n');

		if (ucanMode) {
			AString issuer=RT.ensureString(parsed.getClaims().get(UCAN.ISS));
			AccountKey issuerKey=DID.keyFromDID(issuer);
			if (issuerKey!=null) {
				boolean valid=UCANValidator.validateJWT(token,now,convex.auth.did.DIDVerifier.CONVEX)!=null;
				info.append("UCAN:       ").append(valid?"VALID NOW":"INVALID OR OUTSIDE TIME BOUNDS");
			} else {
				info.append("UCAN:       Issuer binding requires an external DID resolver");
			}
		} else {
			info.append("Time:       ")
				.append(parsed.validateClaims((String)null,null)?"NOT EXPIRED":"EXPIRED");
		}
		infoArea.setText(info.toString());
	}

	private void reset() {
		long now=System.currentTimeMillis()/1000;
		accessIssuedAt.setValue(now);
		accessNotBefore.setValue(now);
		accessExpiry.setValue(now+3600);
		accessUseNotBefore.setSelected(false);
		accessNotBefore.setEnabled(false);
		accessAudience.setText("");
		accessClientID.setText("");
		accessScope.setText("");
		accessTokenID.setText(UUID.randomUUID().toString());
		accessExtraClaims.setText("{}");

		ucanNotBefore.setValue(now);
		ucanExpiry.setValue(now+3600);
		ucanUseNotBefore.setSelected(false);
		ucanNotBefore.setEnabled(false);
		ucanAudience.setText("");
		ucanNonce.setText(newNonce());
		ucanCapabilities.setText("[]");
		ucanProofs.setText("[]");
		ucanFacts.setText("");

		previousSignerDID=null;
		accessIssuer.setText("");
		accessSubject.setText("");
		ucanIssuer.setText("");
		updateSignerDefaults();
		clearOutput();
		infoArea.setText("Choose a token type, complete its claims, then build and sign.");
	}

	private void updateSignerDefaults() {
		AWalletEntry entry=keyCombo.getWalletEntry();
		String next=(entry==null||entry.getPublicKey()==null)
			? null : DID.forKey(entry.getPublicKey()).toString();
		replaceSignerDefault(accessIssuer,next);
		replaceSignerDefault(accessSubject,next);
		replaceSignerDefault(ucanIssuer,next);
		previousSignerDID=next;
	}

	private void replaceSignerDefault(JTextField field,String next) {
		String current=field.getText().trim();
		if (current.isEmpty()||(previousSignerDID!=null&&previousSignerDID.equals(current))) {
			field.setText(next==null?"":next);
		}
	}

	private void clearOutput() {
		tokenArea.setText("");
		headerArea.setText("");
		payloadArea.setText("");
	}

	static AMap<AString,ACell> buildAccessClaims(String issuer,String subject,String audience,
			long issuedAt,Long notBefore,long expiry,String tokenID,String clientID,String scope,
			AMap<AString,ACell> extraClaims) {
		AString iss=requiredString(issuer,"Issuer");
		AString sub=requiredString(subject,"Subject");
		AString aud=requiredString(audience,"Audience");
		AString jti=requiredString(tokenID,"JWT ID");
		AString client=requiredString(clientID,"Client ID");
		if (expiry<=issuedAt) throw new IllegalArgumentException("Expiry must be after issued-at");
		if (notBefore!=null&&notBefore>=expiry) {
			throw new IllegalArgumentException("Not-before must be before expiry");
		}

		AMap<AString,ACell> claims=(extraClaims==null)?Maps.empty():extraClaims;
		for (AString key:ACCESS_RESERVED) {
			if (claims.containsKey(key)) {
				throw new IllegalArgumentException(
					"Additional claims must not redefine '"+key+"'");
			}
		}

		claims=claims.assoc(JWT.ISS,iss);
		claims=claims.assoc(JWT.SUB,sub);
		claims=claims.assoc(JWT.AUD,aud);
		claims=claims.assoc(JWT.IAT,CVMLong.create(issuedAt));
		claims=claims.assoc(JWT.EXP,CVMLong.create(expiry));
		claims=claims.assoc(TOKEN_ID,jti);
		claims=claims.assoc(CLIENT_ID,client);
		if (notBefore!=null) claims=claims.assoc(NOT_BEFORE,CVMLong.create(notBefore));
		if (scope!=null&&!scope.isBlank()) claims=claims.assoc(SCOPE,Strings.create(scope.trim()));
		return claims;
	}

	static AMap<AString,ACell> buildUCANClaims(AccountKey issuerKey,AccountKey audienceKey,
			Long notBefore,
			long expiry,String nonce,AVector<ACell> capabilities,AVector<ACell> proofs,ACell facts) {
		if (issuerKey==null) throw new IllegalArgumentException("Issuer key is required");
		if (audienceKey==null) throw new IllegalArgumentException("Audience key is required");
		AString nnc=requiredString(nonce,"Nonce");
		if (expiry<=0) throw new IllegalArgumentException("Expiry must be positive");
		if (notBefore!=null&&notBefore>=expiry) {
			throw new IllegalArgumentException("Not-before must be before expiry");
		}

		AMap<AString,ACell> claims=UCAN.buildPayload(
			issuerKey,audienceKey,expiry,notBefore,capabilities,proofs,facts);
		return claims.assoc(UCAN.NNC,nnc);
	}

	static AString signToken(AMap<AString,ACell> claims,AKeyPair keyPair,boolean ucanMode) {
		if (!ucanMode) return JWT.signPublic(claims,keyPair);

		AString issuer=RT.ensureString(claims.get(UCAN.ISS));
		AccountKey claimedKey=DID.keyFromDID(issuer);
		if (claimedKey!=null&&!claimedKey.equals(keyPair.getAccountKey())) {
			throw new IllegalArgumentException(
				"UCAN did:key issuer does not match the selected signing key");
		}
		return UCAN.fromPayload(claims,keyPair.sign(Ref.get(claims).getEncoding())).toJWT(keyPair);
	}

	private static AMap<AString,ACell> parseMap(String json,String name) {
		AMap<AString,ACell> map=RT.castMap(JSON.parse(json));
		if (map==null) throw new IllegalArgumentException(name+" must be a JSON object");
		return map;
	}

	private static AVector<ACell> parseVector(String json,String name) {
		AVector<ACell> vector=RT.ensureVector(JSON.parse(json));
		if (vector==null) throw new IllegalArgumentException(name+" must be a JSON array");
		return vector;
	}

	private static ACell parseOptionalJSON(String json,String name) {
		if (json==null||json.isBlank()) return null;
		try {
			return JSON.parse(json);
		} catch (Exception ex) {
			throw new IllegalArgumentException(name+" is not valid JSON: "+message(ex),ex);
		}
	}

	private static AString requiredString(String value,String name) {
		if (value==null||value.isBlank()) throw new IllegalArgumentException(name+" is required");
		return Strings.create(value.trim());
	}

	private static AccountKey requiredDIDKey(String value,String name) {
		AString did=requiredString(value,name);
		AccountKey key=DID.keyFromDID(did);
		if (key==null) throw new IllegalArgumentException(name+" must be a valid did:key");
		return key;
	}

	private static String newNonce() {
		byte[] bytes=new byte[12];
		RANDOM.nextBytes(bytes);
		return Blob.wrap(bytes).toHexString();
	}

	private static long spinnerLong(JSpinner spinner) {
		return ((Number)spinner.getValue()).longValue();
	}

	private static String message(Throwable throwable) {
		String message=throwable.getMessage();
		return (message==null||message.isBlank())?throwable.getClass().getSimpleName():message;
	}

	private static JPanel formPanel() {
		return new JPanel(new MigLayout("fillx,wrap 2,insets 10","[][grow,fill]",""));
	}

	private static void addField(JPanel panel,String label,javax.swing.JComponent field,
			String tooltip) {
		JLabel componentLabel=new JLabel(label);
		componentLabel.setToolTipText(tooltip);
		field.setToolTipText(tooltip);
		panel.add(componentLabel);
		panel.add(field,"growx");
	}

	private static void addOptionalTime(JPanel panel,String label,JCheckBox checkbox,
			JSpinner spinner,String tooltip) {
		JPanel control=new JPanel(new MigLayout("insets 0,fillx","[][grow,fill]",""));
		checkbox.setToolTipText(tooltip);
		spinner.setToolTipText(tooltip);
		control.add(checkbox);
		control.add(spinner,"growx");
		addField(panel,label,control,tooltip);
	}

	private static void addArea(JPanel panel,String label,JTextArea area,String tooltip) {
		JLabel componentLabel=new JLabel(label);
		componentLabel.setToolTipText(tooltip);
		area.setToolTipText(tooltip);
		JScrollPane scroll=new JScrollPane(area);
		panel.add(componentLabel,"top");
		panel.add(scroll,"growx,h 72!");
	}

	private static JSpinner timestampSpinner(long value) {
		JSpinner spinner=new JSpinner(new SpinnerNumberModel(value,0L,Long.MAX_VALUE,1L));
		spinner.setEditor(new JSpinner.NumberEditor(spinner,"0"));
		return spinner;
	}

	private static JTextArea jsonArea(String text) {
		JTextArea area=new JTextArea(text,3,60);
		area.setFont(Toolkit.MONO_FONT);
		area.setLineWrap(true);
		area.setWrapStyleWord(true);
		return area;
	}

	private static CodeLabel outputArea(boolean lineWrap) {
		CodeLabel area=new CodeLabel();
		area.setEditable(false);
		area.setBackground(Color.BLACK);
		area.setFont(Toolkit.MONO_FONT);
		area.setLineWrap(lineWrap);
		area.setWrapStyleWord(false);
		area.setMaxColumns(100);
		return area;
	}
}
