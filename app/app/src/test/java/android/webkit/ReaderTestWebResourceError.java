package android.webkit;

/** WebResourceError's constructor is package-private in the Android SDK. */
public final class ReaderTestWebResourceError extends WebResourceError {
    private final int code;
    private final CharSequence description;

    public ReaderTestWebResourceError(int code, CharSequence description) {
        this.code = code;
        this.description = description;
    }

    @Override public int getErrorCode() { return code; }
    @Override public CharSequence getDescription() { return description; }
}
