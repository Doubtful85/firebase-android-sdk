// Copyright 2021 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.firebase.firestore.core;

import android.text.TextUtils;
import androidx.annotation.Nullable;
import com.google.firebase.firestore.model.Document;
import com.google.firebase.firestore.model.FieldPath;
import com.google.firebase.firestore.util.Function;
import com.google.firestore.v1.StructuredQuery.CompositeFilter.Operator;
import java.util.ArrayList;
import java.util.List;

/** Represents a filter that is the conjunction or disjunction of other filters. */
public class CompositeFilter extends Filter {
  private final List<Filter> filters;
  private final Operator operator;

  // Memoized list of all field filters that can be found by traversing the tree of filters
  // contained in this composite filter.
  private List<FieldFilter> memoizedFlattenedFilters;

  public CompositeFilter(List<Filter> filters, Operator operator) {
    this.filters = filters;
    this.operator = operator;
  }

  @Override
  public List<Filter> getFilters() {
    return filters;
  }

  public Operator getOperator() {
    return operator;
  }

  @Override
  public List<FieldFilter> getFlattenedFilters() {
    if (memoizedFlattenedFilters != null) {
      return memoizedFlattenedFilters;
    }
    memoizedFlattenedFilters = new ArrayList<>();
    for (Filter subfilter : filters) {
      memoizedFlattenedFilters.addAll(subfilter.getFlattenedFilters());
    }
    return memoizedFlattenedFilters;
  }

  /**
   * Returns the first inequality filter contained within this composite filter. Returns {@code
   * null} if it does not contain any inequalities.
   */
  @Override
  public FieldPath getFirstInequalityField() {
    FieldFilter found = findFirstMatchingFilter(f -> f.isInequality());
    if (found != null) {
      return found.getField();
    }
    return null;
  }

  public boolean isConjunction() {
    return operator == Operator.AND;
  }

  public boolean isDisjunction() {
    // TODO(orquery): Replace with Operator.OR.
    return operator == Operator.OPERATOR_UNSPECIFIED;
  }

  /**
   * Returns true if this filter is a conjunction of field filters only. Returns false otherwise.
   */
  public boolean isFlatConjunction() {
    return isFlat() && isConjunction();
  }

  /**
   * Returns true if this filter does not contain any composite filters. Returns false otherwise.
   */
  public boolean isFlat() {
    for (Filter filter : filters) {
      if (filter instanceof CompositeFilter) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns a new composite filter that contains all filter from `this` plus all the given filters.
   */
  public CompositeFilter withAddedFilters(List<Filter> otherFilters) {
    List<Filter> mergedFilters = new ArrayList<>(filters);
    mergedFilters.addAll(otherFilters);
    return new CompositeFilter(mergedFilters, operator);
  }

  /**
   * Performs a depth-first search to find and return the first FieldFilter in the composite filter
   * that satisfies the condition. Returns {@code null} if none of the FieldFilters satisfy the
   * condition.
   */
  @Nullable
  private FieldFilter findFirstMatchingFilter(Function<FieldFilter, Boolean> condition) {
    for (FieldFilter filter : getFlattenedFilters()) {
      if (condition.apply(filter)) {
        return filter;
      }
    }
    return null;
  }

  @Override
  public boolean matches(Document doc) {
    if (isConjunction()) {
      // For conjunctions, all filters must match, so return false if any filter doesn't match.
      for (Filter filter : filters) {
        if (!filter.matches(doc)) {
          return false;
        }
      }
      return true;
    } else {
      // For disjunctions, at least one filter should match.
      for (Filter filter : filters) {
        if (filter.matches(doc)) {
          return true;
        }
      }
      return false;
    }
  }

  @Override
  public String getCanonicalId() {
    // TODO(orquery): Add special case for flat AND filters.

    StringBuilder builder = new StringBuilder();
    builder.append(isConjunction() ? "and(" : "or(");
    builder.append(TextUtils.join(",", filters));
    builder.append(")");
    return builder.toString();
  }

  @Override
  public String toString() {
    return getCanonicalId();
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || !(o instanceof CompositeFilter)) {
      return false;
    }
    CompositeFilter other = (CompositeFilter) o;
    // Note: This comparison requires order of filters in the list to be the same, and it does not
    // remove duplicate subfilters from each composite filter. It is therefore way less expensive.
    // TODO(orquery): Consider removing duplicates and ignoring order of filters in the list.
    return operator == other.operator && filters.equals(other.filters);
  }

  @Override
  public int hashCode() {
    int result = 37;
    result = 31 * result + operator.hashCode();
    result = 31 * result + filters.hashCode();
    return result;
  }
}
