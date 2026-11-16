package qentimart;

import java.util.LinkedHashMap;
import java.util.Map;

/** 资源字节数与 QentimArt 额外开销（.qentimart 目录）的对比。 */
public record StorageReport(long assetBytes, long overheadBytes, double ratio) {
    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("assetBytes", assetBytes);
        m.put("overheadBytes", overheadBytes);
        m.put("ratio", ratio);
        return m;
    }
}
