require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name           = "WyrdHouseholdTrust"
  s.version        = package["version"]
  s.summary        = package["description"]
  s.description    = package["description"]
  s.homepage       = "https://github.com/wyrdsekai/wyrdsekai"
  s.license        = "Apache-2.0"
  s.platforms      = { :ios => "13.0" }
  s.author         = "Wyrdsekai"
  s.source         = { :git => "" }
  s.source_files   = "ios/**/*.{h,m,mm,swift}"

  # This pod ships two legacy bridge modules: WyrdHouseholdTrust (pin store +
  # cert probe) and WyrdRelaySocket (NSURLSessionWebSocketTask relay socket with
  # serverTrust pinning), plus the shared WyrdTrustStore. They rely only on
  # React-Core + system frameworks (Foundation / Security / CommonCrypto), which
  # install_modules_dependencies pulls in. (We no longer #import SocketRocket:
  # RN 0.83 New Architecture does not honour RCTSetCustomSRWebSocketProvider, so
  # the SRSecurityPolicy pinning path was removed in favour of WyrdRelaySocket.)
  install_modules_dependencies(s)
end
