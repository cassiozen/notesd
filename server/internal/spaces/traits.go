package spaces

import (
	"context"
	"fmt"
	"slices"
)

// Trait is a space-level feature that is on for a space (architecture §7.8).
// Worked out from each feature's own state; clients never write them.
type Trait struct {
	Key string `json:"key"`
	// Label names the trait ("Shared"); Summary is its state in a line
	// ("With Ana and Ben"). Apps that don't know Key show these.
	Label   string `json:"label"`
	Summary string `json:"summary"`
	// Exposure is the sentence a move into the space asks with ("Visible to
	// Ana and Ben"); empty when the trait keeps notes to the account.
	Exposure string `json:"exposure,omitempty"`
	// Audience is who Exposure reaches, as stable keys ("user:3", "public"),
	// so apps compare people rather than sentences when a move asks.
	Audience []string `json:"audience,omitempty"`
	// Problem is set when the trait needs attention ("Publishing failed").
	Problem string `json:"problem,omitempty"`
}

// TraitOrder is the order traits show in, everywhere. Keys are reserved before
// their feature ships and new ones appended, so traits never change places.
var TraitOrder = []string{"shared", "published", "agent"}

// A TraitSource reports one trait for the given spaces: an entry for each
// space it is on for. Key is filled in by the Store.
type TraitSource func(ctx context.Context, spaceIDs []string) (map[string]Trait, error)

// AddTrait has the store report key's trait, from src. key must be in
// TraitOrder; adding one twice replaces it.
func (s *Store) AddTrait(key string, src TraitSource) {
	if !slices.Contains(TraitOrder, key) {
		panic(fmt.Sprintf("spaces: trait %q isn't in TraitOrder", key))
	}
	if s.traits == nil {
		s.traits = map[string]TraitSource{}
	}
	s.traits[key] = src
}

// An ExposureSource reports whether key's trait lets anyone but the space's own account read spaceID's
// notes, whoever asks: the rule behind refusing locked notes there (§7.8).
type ExposureSource func(ctx context.Context, spaceID string) (bool, error)

// AddExposure has the store ask src whether key's trait exposes a space. key must be in TraitOrder.
func (s *Store) AddExposure(key string, src ExposureSource) {
	if !slices.Contains(TraitOrder, key) {
		panic(fmt.Sprintf("spaces: trait %q isn't in TraitOrder", key))
	}
	if s.exposures == nil {
		s.exposures = map[string]ExposureSource{}
	}
	s.exposures[key] = src
}

// Exposed reports whether any trait exposes spaceID.
func (s *Store) Exposed(ctx context.Context, spaceID string) (bool, error) {
	for _, key := range TraitOrder {
		src := s.exposures[key]
		if src == nil {
			continue
		}
		if on, err := src(ctx, spaceID); err != nil || on {
			return on, err
		}
	}
	return false, nil
}

// WithTraits sets each space's traits, in TraitOrder: also shared spaces,
// which another file holds, listed with this account's.
func (s *Store) WithTraits(ctx context.Context, list []Space) error {
	ids := make([]string, len(list))
	for i := range list {
		list[i].Traits = []Trait{}
		ids[i] = list[i].ID
	}
	for _, key := range TraitOrder {
		src := s.traits[key]
		if src == nil {
			continue
		}
		on, err := src(ctx, ids)
		if err != nil {
			return fmt.Errorf("trait %s: %w", key, err)
		}
		for i := range list {
			if t, ok := on[list[i].ID]; ok {
				t.Key = key
				list[i].Traits = append(list[i].Traits, t)
			}
		}
	}
	return nil
}
