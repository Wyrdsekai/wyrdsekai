module.exports = function (api) {
  api.cache(true);
  return {
    presets: ["babel-preset-expo"],
    // react-native-worklets/plugin must be the LAST plugin. Required for
    // react-native-worklets >= 0.7 and react-native-reanimated 4.x — without
    // it the worklets transform never runs, the JSI install() callback gets
    // null at runtime, and the JS bundle errors out before any screen mounts.
    plugins: ["react-native-worklets/plugin"],
  };
};
