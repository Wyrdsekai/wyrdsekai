require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name           = "WyrdOnnx"
  s.version        = package["version"]
  s.summary        = package["description"]
  s.description    = package["description"]
  s.homepage       = "https://github.com/wyrdsekai/wyrdsekai"
  s.license        = "Apache-2.0"
  s.platforms      = { :ios => "13.0" }
  s.author         = "Wyrdsekai"
  s.source         = { :git => "" }
  s.source_files   = "ios/**/*.{h,m,mm,swift}"

  # Real ORT iOS bindings — not the broken RN wrapper.
  # NOTE: CocoaPods (onnxruntime-objc) never published 1.23.2 — its 1.23.x line
  # is 1.23.0 only (next is 1.24.1). Android's Maven onnxruntime-android DOES
  # have 1.23.2; the versions intentionally differ per registry availability.
  s.dependency "onnxruntime-objc", "1.23.0"

  install_modules_dependencies(s)
end
