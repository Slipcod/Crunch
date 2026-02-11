package redempt.crunch.data;

/**
 * Utility class with some methods for parsing base 10 numbers (only ints and doubles for now) that are faster than the standard Java implementation
 */
public class FastNumberParsing {

	// Lookup tables to avoid Math.pow in hot path (for scientific notation and fractional part)
	private static final int EXPONENT_TABLE_MIN = -10;
	private static final int EXPONENT_TABLE_MAX = 10;
	private static final int MAX_FRACTIONAL_DIGITS = 10;
	private static final double[] POW10_BY_EXPONENT = new double[EXPONENT_TABLE_MAX - EXPONENT_TABLE_MIN + 1];
	private static final double[] POW10_INVERSE = new double[MAX_FRACTIONAL_DIGITS + 1]; // 10^-1 .. 10^-22

	static {
		for (int i = 0; i < POW10_BY_EXPONENT.length; i++) {
			POW10_BY_EXPONENT[i] = Math.pow(10, EXPONENT_TABLE_MIN + i);
		}
		for (int i = 1; i < POW10_INVERSE.length; i++) {
			POW10_INVERSE[i] = Math.pow(10, -i);
		}
	}

	/**
	 * Parse an integer from base 10 string input
	 * @param input The base 10 string input
	 * @return The parsed integer
	 */
	public static int parseInt(String input) {
		return parseInt(input, 0, input.length());
	}

	/**
	 * Parse an integer from base 10 string input
	 * @param input The base 10 string input
	 * @param start The starting index to parse from, inclusive
	 * @param end The ending index to parse to, exclusive
	 * @return The parsed integer
	 */
	public static int parseInt(String input, int start, int end) {
		if (start == end) {
			throw new NumberFormatException("Zero-length input");
		}
		int i = start;
		boolean negative = false;
		if (input.charAt(i) == '-') {
			negative = true;
			i++;
		}
		int output = 0;
		for (; i < end; i++) {
			char c = input.charAt(i);
			if (c > '9' || c < '0') {
				throw new NumberFormatException("Non-numeric character in input '" + input.substring(start, end) + "'");
			}
			output *= 10;
			output += c - '0';
		}
		return negative ? -output: output;
	}

	/**
	 * Parse a double from base 10 string input (no NaN or Infinity)
	 * @param input The base 10 string input
	 * @return The parsed double
	 */
	public static double parseDouble(String input) {
		return parseDouble(input, 0, input.length());
	}

	/**
	 * Parse a double from base 10 string input (no NaN or Infinity).
	 * Supports scientific notation: 1.5e10, 2E-3, .5e+2, etc.
	 * @param input The base 10 string input
	 * @param start The starting index to parse from, inclusive
	 * @param end The ending index to parse to, exclusive
	 * @return The parsed double
	 */
	public static double parseDouble(String input, int start, int end) {
		if (start == end) {
			throw new NumberFormatException("Zero-length input");
		}
		int eIndex = indexOfExponentMarker(input, start, end);
		if (eIndex == end) {
			return parseMantissaOnly(input, start, end);
		} else {
			double mantissa = parseMantissaOnly(input, start, eIndex);
			int exponent = parseExponentAfterE(input, eIndex + 1, end, start, end);
			return mantissa * powerOf10(exponent);
		}
	}

	/**
	 * Parses exponent after 'e'/'E': optional '+'/'-', then digits.
	 * @param fullStart, fullEnd used only for error messages
	 */
	private static int parseExponentAfterE(String input, int expStart, int end, int fullStart, int fullEnd) {
		if (expStart == end) {
			throw new NumberFormatException("Exponent expected after 'e' in input '" + input.substring(fullStart, fullEnd) + "'");
		}
		char sign = input.charAt(expStart);
		boolean negative = sign == '-';
		if (sign == '-' || sign == '+') {
			expStart++;
		}
		if (expStart == end) {
			throw new NumberFormatException("Exponent digits expected in input '" + input.substring(fullStart, fullEnd) + "'");
		}
		int exponent = parseInt(input, expStart, end);
		return negative ? -exponent : exponent;
	}

	/**
	 * Parse a double from base 10 string input, only real number values are supported (no NaN, Infinity or scientific notation)
	 * @param input The base 10 string input
	 * @param start The starting index to parse from, inclusive
	 * @param end The ending index to parse to, exclusive
	 * @return The parsed double
	 */
	private static double parseMantissaOnly(String input, int start, int end) {
		if (start == end) {
			throw new NumberFormatException("Zero-length input");
		}
		int i = start;
		boolean negative = false;
		if (input.charAt(start) == '-') {
			negative = true;
			i++;
		}
		double output = 0;
		double after = 0;
		int decimal = -1;
		while (i < end) {
			char c = input.charAt(i);
			if (c == '.') {
				if (decimal != -1) {
					throw new NumberFormatException("Second period in double for input '" + input + "'");
				}
				decimal = i;
				i++;
				continue;
			}
			if (c < '0' || c > '9') {
				throw new NumberFormatException("Non-numeric character in input '" + input + "'");
			}
			int digit = c - '0';
			if (decimal != -1) {
				after = after * 10 + digit;
			} else {
				output = output * 10 + digit;
			}
			i++;
		}
		if (decimal != -1) {
			int fractionalDigitCount = end - decimal - 1;
			after *= scaleForFractionalDigits(fractionalDigitCount);
		}

		double value = output + after;
		return negative ? -value : value;
	}

	/**
	 * Returns the index of the first 'e' or 'E' in the given range, or end if none.
	 * @param input the string to search in
	 * @param start the starting index, inclusive
	 * @param end the ending index, exclusive
	 * @return index of first 'e' or 'E', or end if not found
	 */
	private static int indexOfExponentMarker(String input, int start, int end) {
		for (int i = start; i < end; i++) {
			char c = input.charAt(i);
			if (c == 'e' || c == 'E') {
				return i;
			}
		}
		return end;
	}

	/**
	 * Returns 10^exponent. Uses lookup table when exponent is in [EXPONENT_TABLE_MIN, EXPONENT_TABLE_MAX], otherwise Math.pow.
	 * @param exponent the exponent (e.g. from scientific notation after 'e' or 'E')
	 * @return 10 raised to the given exponent
	 */
	private static double powerOf10(int exponent) {
		if (exponent >= EXPONENT_TABLE_MIN && exponent <= EXPONENT_TABLE_MAX) {
			return POW10_BY_EXPONENT[exponent - EXPONENT_TABLE_MIN];
		}
		return Math.pow(10, exponent);
	}

	/**
	 * Returns 10^(-digitCount) for scaling the fractional part of a decimal. Uses table when digitCount is in 1..MAX_FRACTIONAL_DIGITS.
	 * @param digitCount number of digits after the decimal point
	 * @return 10^(-digitCount)
	 */
	private static double scaleForFractionalDigits(int digitCount) {
		if (digitCount > 0 && digitCount <= MAX_FRACTIONAL_DIGITS) {
			return POW10_INVERSE[digitCount];
		}
		return Math.pow(10, -digitCount);
	}
}
