package tv.anitv;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

final class AccountCodec {
    static byte[] fields(String first,String second) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();CodedOutputStream out=CodedOutputStream.newInstance(bytes);
        out.writeString(1,first);out.writeString(2,second);out.flush();return bytes.toByteArray();
    }
    static String csv(byte[] bytes) {StringBuilder out=new StringBuilder();for(byte b:bytes){if(out.length()>0)out.append(',');out.append(b&255);}return out.toString();}
    static String token(byte[] bytes) throws IOException {
        WirePayload payload = WirePayload.read(bytes);
        if (payload.json instanceof org.json.JSONObject) {
            try {org.json.JSONObject json=(org.json.JSONObject) payload.json;
                String reason = WirePayload.error(json, "登录失败");
                if (reason != null) throw new IOException(reason);
                Object token = json.getJSONObject("body").opt("token");
                if (!(token instanceof String) || ((String) token).trim().isEmpty()) throw new IOException("登录响应缺少会话");
                return (String) token;
            }catch(org.json.JSONException e){throw new IOException("登录响应格式不匹配",e);}
        }
        CodedInputStream in=CodedInputStream.newInstance(payload.bytes);boolean error=false;String message="登录失败",token="";
        while(!in.isAtEnd()){int tag=in.readTag();if(tag==8)error=in.readBool();else if(tag==18)message=in.readStringRequireUtf8();
            else if(tag==26){CodedInputStream body=CodedInputStream.newInstance(in.readByteArray());while(!body.isAtEnd()){int field=body.readTag();if(field==18)token=body.readStringRequireUtf8();else if(!body.skipField(field))break;}}
            else if(!in.skipField(tag))break;}
        if(error || token.isEmpty())throw new IOException(message.trim().isEmpty() ? "登录失败" : message);return token;
    }
}
