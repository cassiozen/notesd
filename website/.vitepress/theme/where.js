// Get started's pages mark parts for one kind of server with <div data-where="…">; once the reader
// picks theirs, CSS hides the rest.
export const WHERE = { home: "Home server", vps: "VPS", fly: "Fly.io" };
export const WHERE_KEY = "notesd-where";

// Inline in <head>, so a remembered choice applies before the page paints.
export const whereScript = `try{var w=localStorage.getItem(${JSON.stringify(WHERE_KEY)});if(${JSON.stringify(Object.keys(WHERE))}.indexOf(w)>=0)document.documentElement.classList.add("where-"+w)}catch(e){}`;
