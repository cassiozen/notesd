import DefaultTheme from "vitepress/theme";
import WherePicker from "./WherePicker.vue";
import "./custom.css";

export default {
  extends: DefaultTheme,
  enhanceApp({ app }) {
    app.component("WherePicker", WherePicker);
  },
};
