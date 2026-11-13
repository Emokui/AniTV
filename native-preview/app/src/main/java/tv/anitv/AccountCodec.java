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
        bytes=WirePayload.unpack(bytes);
        if(bytes.length>0 && bytes[0]=='{') {
            try {org.json.JSONObject json=new org.json.JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
                if(json.optBoolean("error"))throw new IOException(json.optString("message","登录失败"));
                String token=json.getJSONObject("body").optString("token");if(token.isEmpty())throw new IOException("登录响应缺少会话");return token;
            }catch(org.json.JSONException e){throw new IOException("登录响应格式不匹配",e);}
        }
        CodedInputStream in=CodedInputStream.newInstance(bytes);boolean error=false;String message="登录失败",token="";
        while(!in.isAtEnd()){int tag=in.readTag();if(tag==8)error=in.readBool();else if(tag==18)message=in.readStringRequireUtf8();
            else if(tag==26){CodedInputStream body=CodedInputStream.newInstance(in.readByteArray());while(!body.isAtEnd()){int field=body.readTag();if(field==18)token=body.readStringRequireUtf8();else if(!body.skipField(field))break;}}
            else if(!in.skipField(tag))break;}
        if(error || token.isEmpty())throw new IOException(message);return token;
    }
}
