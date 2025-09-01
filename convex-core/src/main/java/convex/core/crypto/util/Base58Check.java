/*
 * Adapted from TezosJ library by Mike Anderson under Apache 2.0 License
 */

package convex.core.crypto.util;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;

public class Base58Check
{
    private static String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static char[] ALPHABET_ARRAY = ALPHABET.toCharArray();
    private static BigInteger BASE_SIZE = BigInteger.valueOf(ALPHABET_ARRAY.length);
    private static int CHECKSUM_SIZE = 4;

    /**
     * Encode in Base58 after adding a 4-byte checksum
     * @param data
     * @return Encoded Base58 String with checksum
     */
    public static String encode(byte[] data) 
    {
        try {
			return encodePlain(addChecksum(data));
		} catch (NoSuchAlgorithmException e) {
			throw new UnsupportedOperationException("Missing algorithm",e);
		}
    }

    public static String encodePlain(byte[] data)
    {
        BigInteger intData;

        try
        {
            intData = new BigInteger(1, data);
        }
        catch (NumberFormatException e)
        {
            return "";
        }

        String result = "";

        while (intData.compareTo(BigInteger.ZERO) == 1)
        {
            BigInteger[] quotientAndRemainder = intData.divideAndRemainder(BASE_SIZE);

            BigInteger quotient = quotientAndRemainder[0];
            BigInteger remainder = quotientAndRemainder[1];

            intData = quotient;

            result = ALPHABET_ARRAY[remainder.intValue()] + result;
        }

        for (int i = 0; i < data.length && data[i] == 0; i++)
        {
            result = '1' + result;
        }

        return result;
    }


    public static byte[] decode(String encoded)
    {
        byte[] valueWithChecksum = decodePlain(encoded);

        byte[] value = verifyAndRemoveChecksum(valueWithChecksum);

        if (value == null)
        {
            throw new IllegalArgumentException("Base58 checksum is invalid");
        }

        return value;
    }

    public static byte[] decodePlain(String encoded)
    {
        if (encoded.length() == 0)
        {
            return new byte[0];
        }
        
        BigInteger intData = BigInteger.ZERO;
        int leadingZeros = 0;

        for (int i = 0; i < encoded.length(); i++)
        {
            char current = encoded.charAt(i);

            int digit = ALPHABET.indexOf(current);

            if (digit == -1)
            {
                throw new IllegalArgumentException(String.format(Locale.ENGLISH,"Invalid Base58 character `%c` at position %d", current, i));
            }

            intData = (intData.multiply(BASE_SIZE)).add(BigInteger.valueOf(digit));
        }

        for (int i = 0; i < encoded.length(); i++)
        {
            char current = encoded.charAt(i);

            if (current == '1')
            {
                leadingZeros++;
            }
            else
            {
                break;
            }
        }

        byte[] bytesData;
        if (intData.equals(BigInteger.ZERO))
        {
            bytesData = new byte[0];
        }
        else
        {
            bytesData = intData.toByteArray();
        }

        //Should we cut the sign byte ? - https://bitcoinj.googlecode.com/git-history/216deb2d35d1a128a7f617b91f2ca35438aae546/lib/src/com/google/bitcoin/core/Base58.java
        boolean stripSignByte = bytesData.length > 1 && bytesData[0] == 0 && bytesData[1] < 0;

        byte[] decoded = new byte[bytesData.length - (stripSignByte ? 1 : 0) + leadingZeros];

        System.arraycopy(bytesData, stripSignByte ? 1 : 0, decoded, leadingZeros, decoded.length - leadingZeros);

        return decoded;
    }

    private static byte[] verifyAndRemoveChecksum(byte[] data) 
    {
        byte[] value = Arrays.copyOfRange(data, 0, data.length - CHECKSUM_SIZE);
        byte[] checksum = Arrays.copyOfRange(data, data.length - CHECKSUM_SIZE, data.length);
        byte[] expectedChecksum = getChecksum(value);

        return Arrays.equals(checksum, expectedChecksum) ? value : null;
    }

    private static byte[] addChecksum(byte[] data) throws NoSuchAlgorithmException
    {
        byte[] checksum = getChecksum(data);

        byte[] result = new byte[data.length + CHECKSUM_SIZE];

        System.arraycopy(data, 0, result, 0, data.length);
        System.arraycopy(checksum, 0, result, data.length, CHECKSUM_SIZE);

        return result;
    }

    private static byte[] getChecksum(byte[] data) 
    {
        byte[] hash = hash256(data);
        hash = hash256(hash);

        return Arrays.copyOfRange(hash, 0, CHECKSUM_SIZE);
    }

    public static byte[] hash256(byte[] data) 
    {
		try {
			MessageDigest md;
			md = MessageDigest.getInstance("SHA-256");
	        return md.digest(data);
		} catch (NoSuchAlgorithmException e) {
			throw new UnsupportedOperationException("SHA256 not available?",e);
		}
    }


}
