package qentimart;

/** QentimArt 下游 SDK 的基础异常。服务端有响应时 {@link #status} 为 HTTP 状态码（否则为 -1）。 */
public class QentimArtException extends RuntimeException {
    /** HTTP 状态码；无服务端响应时为 -1。 */
    public final int status;
    /** 机器可读的错误码（如 ECANCELED / ECONFLICT / ETRUNCATED / ECORRUPT / EMISSING / ELOCKED / ENETWORK），无则为 null。 */
    public final String code;

    public QentimArtException(String message) {
        this(message, -1, null);
    }

    public QentimArtException(String message, int status, String code) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public QentimArtException(String message, Throwable cause) {
        super(message, cause);
        this.status = -1;
        this.code = null;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
