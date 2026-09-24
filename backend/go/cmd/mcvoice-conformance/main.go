// Command mcvoice-conformance runs the backend conformance suite against a
// backend binary (Rust or Go), launching it with a test environment.
//
//	mcvoice-conformance -name rust -- ../rust/target/release/mcvoice-backend
package main

import (
	"flag"
	"fmt"
	"os"
	"regexp"
	"sort"

	"github.com/RavoxX/MCVoice/backend/go/pkg/conformance"
)

func main() {
	name := flag.String("name", "backend", "label for the report")
	run := flag.String("run", "", "only run scenarios matching this regexp")
	flag.Parse()
	argv := flag.Args()
	if len(argv) == 0 {
		fmt.Fprintln(os.Stderr, "usage: mcvoice-conformance [-name N] [-run RE] -- <backend command...>")
		os.Exit(2)
	}
	var only func(string) bool
	if *run != "" {
		re := regexp.MustCompile(*run)
		only = re.MatchString
	}
	failed := 0
	total := 0
	for _, mode := range []string{"offline", "mojang"} {
		target, stop, err := conformance.Launch(*name, argv, mode)
		if err != nil {
			fmt.Fprintf(os.Stderr, "launch %s (%s): %v\n", *name, mode, err)
			os.Exit(1)
		}
		res := conformance.Run(target, mode, only)
		stop()
		names := make([]string, 0, len(res))
		for n := range res {
			names = append(names, n)
		}
		sort.Strings(names)
		for _, n := range names {
			total++
			if err := res[n]; err != nil {
				failed++
				fmt.Printf("FAIL  %-8s %-60s %v\n", mode, n, err)
			} else {
				fmt.Printf("PASS  %-8s %s\n", mode, n)
			}
		}
	}
	fmt.Printf("\n%s: %d/%d scenarios passed\n", *name, total-failed, total)
	if failed > 0 {
		os.Exit(1)
	}
}
