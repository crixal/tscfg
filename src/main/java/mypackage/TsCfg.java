// auto-generated by tscfg on Fri Jan 08 17:30:04 PST 2016

package mypackage;

import com.typesafe.config.Config;

public class TsCfg {
  public final Endpoint endpoint;
  public static class Endpoint {
    public final Interface interface_;
    public static class Interface {
      public final int port;
      public Interface(Config c) {
        this.port = c.hasPath("port") ? c.getInt("port") : 8080; // "Int | 8080" 
      }
      public String toString() { return toString(""); }
      public String toString(String ind) {
      return
          ind + "port = " + this.port + "\n";
      }
    }
    public final String name;
    public final String path;
    public final Integer serial;
    public final String url;
    public Endpoint(Config c) {
      this.interface_ = new Interface(c.getConfig("interface"));
      this.name = c.hasPath("name") ? c.getString("name") : null; // "String?" 
      this.path = c.hasPath("path") ? c.getString("path") : "/"; // "string | /" 
      this.serial = c.hasPath("serial") ? Integer.valueOf(c.getInt("serial")) : null; // "int?" 
      this.url = c.hasPath("url") ? c.getString("url") : "http://example.net"; // "String | http://example.net" 
    }
    public String toString() { return toString(""); }
    public String toString(String ind) {
    return
        ind + "interface_:\n" + this.interface_.toString(ind + "  ") + "\n"
       +ind + "name = " + this.name + "\n"
       +ind + "path = " + this.path + "\n"
       +ind + "serial = " + this.serial + "\n"
       +ind + "url = " + this.url + "\n";
    }
  }
  public TsCfg(Config c) {
    this.endpoint = new Endpoint(c.getConfig("endpoint"));
  }
  public String toString() { return toString(""); }
  public String toString(String ind) {
  return
      ind + "endpoint:\n" + this.endpoint.toString(ind + "  ") + "\n";
  }
}
