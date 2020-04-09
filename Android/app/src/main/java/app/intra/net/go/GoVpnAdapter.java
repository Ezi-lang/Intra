/*
Copyright 2019 Jigsaw Operations LLC

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package app.intra.net.go;

import android.content.Context;
import android.content.res.Resources;
import android.net.VpnService;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.intra.R;
import app.intra.sys.IntraVpnService;
import app.intra.sys.PersistentState;
import app.intra.sys.VpnController;
import app.intra.sys.firebase.LogWrapper;
import doh.Transport;
import java.io.IOException;
import java.util.Locale;
import protect.Protector;
import tun2socks.Tun2socks;
import tunnel.IntraTunnel;

/**
 * This is a VpnAdapter that captures all traffic and routes it through a go-tun2socks instance with
 * custom logic for Intra.
 */
public class GoVpnAdapter {
  private static final String LOG_TAG = "GoVpnAdapter";

  // This value must match the hardcoded MTU in outline-go-tun2socks.
  // TODO: Make outline-go-tun2socks's MTU configurable.
  private static final int VPN_INTERFACE_MTU = 1500;
  private static final int DNS_DEFAULT_PORT = 53;

  // IPv4 VPN constants
  private static final String IPV4_TEMPLATE = "10.111.222.%d";
  private static final int IPV4_PREFIX_LENGTH = 24;

  // The VPN service and tun2socks must agree on the layout of the network.  By convention, we
  // assign the following values to the final byte of an address within a subnet.
  private enum LanIp {
    GATEWAY(1), ROUTER(2), DNS(3);

    // Value of the final byte, to be substituted into the template.
    private final int value;

    LanIp(int value) {
      this.value = value;
    }

    String make(String template) {
      return String.format(Locale.ROOT, template, value);
    }
  }

  public static final String FAKE_DNS_IP = LanIp.DNS.make(IPV4_TEMPLATE);

  // Service context in which the VPN is running.
  private final IntraVpnService vpnService;

  // TUN device representing the VPN.
  private ParcelFileDescriptor tunFd;

  // The Intra session object from go-tun2socks.  Initially null.
  private IntraTunnel tunnel;
  private GoIntraListener listener;

  public static GoVpnAdapter establish(@NonNull IntraVpnService vpnService) {
    ParcelFileDescriptor tunFd = establishVpn(vpnService);
    if (tunFd == null) {
      return null;
    }
    return new GoVpnAdapter(vpnService, tunFd);
  }

  private GoVpnAdapter(IntraVpnService vpnService, ParcelFileDescriptor tunFd) {
    this.vpnService = vpnService;
    this.tunFd = tunFd;
  }

  public synchronized void start() {
    connectTunnel();
  }

  private void connectTunnel() {
    if (tunnel != null) {
      return;
    }
    // VPN parameters
    final String fakeDns = FAKE_DNS_IP + ":" + DNS_DEFAULT_PORT;

    // Strip leading "/" from ip:port string.
    listener = new GoIntraListener(vpnService);
    String dohURL = PersistentState.getServerUrl(vpnService);

    try {
      LogWrapper.log(Log.INFO, LOG_TAG, "Starting go-tun2socks");
      Transport transport = makeDohTransport(dohURL);
      tunnel = Tun2socks.connectIntraTunnel(tunFd.getFd(), fakeDns,
          transport, getProtector(), listener);
    } catch (Exception e) {
      LogWrapper.logException(e);
      tunnel = null;
      VpnController.getInstance().onConnectionStateChanged(vpnService, IntraVpnService.State.FAILING);
    }
  }

  private static ParcelFileDescriptor establishVpn(IntraVpnService vpnService) {
    try {
      VpnService.Builder builder = vpnService.newBuilder()
          .setSession("Intra go-tun2socks VPN")
          .setMtu(VPN_INTERFACE_MTU)
          .addAddress(LanIp.GATEWAY.make(IPV4_TEMPLATE), IPV4_PREFIX_LENGTH)
          .addRoute("0.0.0.0", 0)
          .addDnsServer(LanIp.DNS.make(IPV4_TEMPLATE));
      if (VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP) {
        builder.addDisallowedApplication(vpnService.getPackageName());
      }
      return builder.establish();
    } catch (Exception e) {
      LogWrapper.logException(e);
      return null;
    }
  }

  private @Nullable Protector getProtector() {
    if (VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP) {
      // We don't need socket protection in these versions because the call to
      // "addDisallowedApplication" effectively protects all sockets in this app.
      return null;
    }
    return vpnService;
  }

  public synchronized void close() {
    if (tunnel != null) {
      tunnel.disconnect();
    }
    if (tunFd != null) {
      try {
        tunFd.close();
      } catch (IOException e) {
        LogWrapper.logException(e);
      }
    }
    tunFd = null;
  }

  private doh.Transport makeDohTransport(@Nullable String url) throws Exception {
    @NonNull String realUrl = PersistentState.expandUrl(vpnService, url);
    String dohIPs = getIpString(vpnService, realUrl);
    return Tun2socks.newDoHTransport(realUrl, dohIPs, getProtector(), listener);
  }

  /**
   * Updates the DOH server URL for the VPN.  If Go-DoH is enabled, DNS queries will be handled in
   * Go, and will not use the Java DoH implementation.  If Go-DoH is not enabled, this method
   * has no effect.
   */
  public synchronized void updateDohUrl() {
    if (tunFd == null) {
      // Adapter is closed.
      return;
    }
    if (tunnel == null) {
      // Attempt to re-create the tunnel.  Creation may have failed originally because the DoH
      // server could not be reached.  This will update the DoH URL as well.
      connectTunnel();
      return;
    }
    // Overwrite the DoH Transport with a new one, even if the URL has not changed.  This function
    // is called on network changes, and it's important to switch to a fresh transport because the
    // old transport may be using sockets on a deleted interface, which may block until they time
    // out.
    String url = PersistentState.getServerUrl(vpnService);
    try {
      tunnel.setDNS(makeDohTransport(url));
    } catch (Exception e) {
      LogWrapper.logException(e);
      tunnel.disconnect();
      tunnel = null;
      VpnController.getInstance().onConnectionStateChanged(vpnService, IntraVpnService.State.FAILING);
    }
  }

  static String getIpString(Context context, String url) {
    Resources res = context.getResources();
    String[] urls = res.getStringArray(R.array.urls);
    String[] ips = res.getStringArray(R.array.ips);
    for (int i = 0; i < urls.length; ++i) {
      // TODO: Consider relaxing this equality condition to a match on just the domain.
      if (urls[i].equals(url)) {
        return ips[i];
      }
    }
    return "";
  }
}
